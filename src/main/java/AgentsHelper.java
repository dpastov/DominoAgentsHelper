import lotus.domino.Session;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import lotus.domino.Base;
import lotus.domino.Database;
import lotus.domino.View;
import lotus.domino.Document;
import lotus.domino.NotesException;
import lotus.domino.NotesFactory;
import lotus.notes.addins.JavaServerAddin;
import lotus.notes.internal.MessageQueue;

public class AgentsHelper extends JavaServerAddin {
	protected static final int 		MQ_MAX_MSGSIZE 			= 1024;
	protected MessageQueue 			mq						= null;
	private int 					dominoTaskID			= 0;

	final String JADDIN_NAME = "AgentsHelper";
	final String JADDIN_VERSION = "1.0.1";
	final String JADDIN_DATE = "2023-10-22 18:30 CET";

	// Instance variables
	private Session m_session = null;
	private String m_filePath = "agentshelper.nsf";
	private List<HashMap<String, Object>> m_events = null;

	// we expect our first parameter is dedicated for secondsElapsed
	public AgentsHelper(String[] args) {
		m_filePath = args[0];
	}

	// constructor if no parameters
	public AgentsHelper() {
	}

	public void runNotes() {
		try {
			m_session = NotesFactory.createSession();
			Database database = m_session.getDatabase(null, m_filePath);
			if (database == null || !database.isOpen()) {
				logMessage("(!) LOAD FAILED - database not found: " + m_filePath);
				return;
			}
			
			// Set the Java thread name to the class name (default would be "Thread-n")
			this.setName(this.getJavaAddinName());
			// Create the status line showed in 'Show Task' console command
			this.dominoTaskID = createAddinStatusLine(this.getJavaAddinName());

			updateCommands();

			showInfo();
			
			listen();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	protected void listen() {
		StringBuffer qBuffer = new StringBuffer(MQ_MAX_MSGSIZE);

		try {
			mq = new MessageQueue();
			int messageQueueState = mq.create(this.getQName(), 0, 0);	// use like MQCreate in API
			if (messageQueueState == MessageQueue.ERR_DUPLICATE_MQ) {
				logMessage(this.getJavaAddinName() + " task is already running");
				return;
			}

			if (messageQueueState != MessageQueue.NOERROR) {
				logMessage("Unable to create the Domino message queue");
				return;
			}

			if (mq.open(this.getQName(), 0) != MessageQueue.NOERROR) {
				logMessage("Unable to open Domino message queue");
				return;
			}

			setAddinState("Active");
			
			this.eventsFireOnStart();	// start events before loop
			while (this.addInRunning() && (messageQueueState != MessageQueue.ERR_MQ_QUITTING)) {
				/* gives control to other task in non preemptive os*/
				OSPreemptOccasionally();

				// check for command from console
				messageQueueState = mq.get(qBuffer, MQ_MAX_MSGSIZE, MessageQueue.MQ_WAIT_FOR_MSG, 1000);
				if (messageQueueState == MessageQueue.ERR_MQ_QUITTING) {
					return;
				}

				// check messages for Genesis
				String cmd = qBuffer.toString().trim();
				if (!cmd.isEmpty()) {
					resolveMessageQueueState(cmd);
				};

				// check if we need to run events
				eventsFire();
			}
		} catch(Exception e) {
			logMessage(e.getMessage());
		}
	}
	
	protected boolean resolveMessageQueueState(String cmd) {
		boolean flag = true;

		if ("-h".equals(cmd) || "help".equals(cmd)) {
			showHelp();
		}
		else if ("quit".equals(cmd)) {
			quit();
		}
		else if ("info".equals(cmd)) {
			showInfo();
		}
		else if ("update".equals(cmd)) {
			updateCommands();
			logMessage("update - completed");
		}
		else if ("fire".equals(cmd)) {
			eventsFireForce();
		}
		else {
			flag = false;
		}

		return flag;
	}

	private void updateCommands() {
		try {
			Database database = m_session.getDatabase(null, m_filePath);
			if (database == null || !database.isOpen()) {
				logMessage("(!) LOAD FAILED - database not found: " + m_filePath);
				return;
			}

			m_events = new ArrayList<HashMap<String, Object>>();

			View view = database.getView("Commands");
			Document doc = view.getFirstDocument();
			while (doc != null) {
				Document docNext = view.getNextDocument(doc);

				// start new thread for each agent
				String name = doc.getItemValueString("Name");
				String json = doc.getItemValueString("JSON");

				JSONObject obj = getJSONObject(json);
				if (obj != null) {
					String command = (String) obj.get("command");	// required
					Long interval = (Long) obj.get("interval");		// required
					boolean runOnStart = obj.containsKey("runOnStart") && (Boolean) obj.get("runOnStart");	// optional
					String runIf = (String) obj.get("runIf");		// optional

					HashMap<String, Object> event = new HashMap<String, Object>();
					event.put("command", command);
					event.put("interval", interval);
					event.put("runOnStart", runOnStart);
					event.put("runIf", runIf);
					event.put("lastRun", new Date());

					m_events.add(event);
				}
				else {
					logMessage(name + ": invalid json");
				}

				recycle(doc);
				doc = docNext;
			}

			recycle(view);
			recycle(database);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	protected void showHelp() {
		logMessage("*** Usage ***");
		logMessage("load runjava " + this.getJavaAddinName());
		logMessage("tell " + this.getJavaAddinName() + " <agentshelper.nsf>");
		logMessage("   quit             Unload addin");
		logMessage("   help             Show help information (or -h)");
		logMessage("   info             Show version");
		logMessage("   fire            	Fire all agents from config");
		logMessage("   update           Update config from <agentshelper.nsf>");

		int year = Calendar.getInstance().get(Calendar.YEAR);
		logMessage("Copyright (C) Prominic.NET, Inc. 2023" + (year > 2023 ? " - " + Integer.toString(year) : ""));
		logMessage("See https://prominic.net for more details.");
	}

	protected void quit() {
		this.stopAddin();
	}

	protected String getQName() {
		return MSG_Q_PREFIX + getJavaAddinName().toUpperCase();
	}

	protected String getJavaAddinName() {
		return this.getClass().getName();
	}

	private void eventsFireOnStart() {
		for (int i = 0; i < m_events.size(); i++) {
			HashMap<String, Object> event = m_events.get(i);
			boolean runOnStart = (Boolean) event.get("runOnStart");
			if (runOnStart) {
				eventFire(event);
			}
		}
	}

	private void eventsFire() {
		Date now = new Date();

		for (int i = 0; i < m_events.size(); i++) {
			HashMap<String, Object> event = m_events.get(i);

			Date lastRun = (Date) event.get("lastRun");
			Long interval = (Long) event.get("interval");

			long seconds = (now.getTime()-lastRun.getTime())/1000;
			if (seconds > interval) {
				eventFire(event);
			};
		}
	}

	private void eventsFireForce() {
		for (int i = 0; i < m_events.size(); i++) {
			HashMap<String, Object> event = m_events.get(i);
			eventFire(event);
		}
	}

	private void eventFire(HashMap<String, Object> event) {
		try {
			String command = (String) event.get("command");
			m_session.sendConsoleCommand("", command);
			event.put("lastRun", new Date());
		} catch (NotesException e) {
			e.printStackTrace();
		}
	}

	/**
	 * JSONObject
	 */
	private JSONObject getJSONObject(String json) {
		JSONParser parser = new JSONParser();
		try {
			return (JSONObject) parser.parse(json);
		} catch (ParseException e) {
			return null;
		}
	}

	/**
	 * Display run configuration
	 */
	private void showInfo() {
		String jvmVersion = System.getProperty("java.specification.version", "0");

		logMessage("version			" + JADDIN_VERSION);
		logMessage("build date		" + JADDIN_DATE);
		logMessage("java			" + jvmVersion);
		logMessage("config database	" + m_filePath);
		logMessage("active events	" + m_events.size());
	}

	/**
	 * Write a log message to the Domino console. The message string will be
	 * prefixed with the add-in name followed by a column, e.g.
	 * <code>"AddinName: xxxxxxxx"</code>
	 * 
	 * @param message Message to be displayed
	 */
	private final void logMessage(String message) {
		AddInLogMessageText(this.JADDIN_NAME + ": " + message, 0);
	}

	/**
	 * Recycle Domino objects.
	 */
	private static void recycle(Base object) throws NotesException {
		if (object == null)
			return;
		object.recycle();
		object = null;
	}

	/**
	 * Set the text of the add-in which is shown in command <code>"show tasks"</code>.
	 *
	 * @param	text	Text to be set
	 */
	protected final void setAddinState(String text) {
		if (this.dominoTaskID == 0) return;

		AddInSetStatusLine(this.dominoTaskID, text);
	}

	/**
	 * Create the Domino task status line which is shown in <code>"show tasks"</code> command.
	 *
	 * Note: This method is also called by the JAddinThread and the user add-in
	 *
	 * @param	name	Name of task
	 * @return	Domino task ID
	 */
	protected final int createAddinStatusLine(String name) {
		return (AddInCreateStatusLine(name));
	}

	@Override
	public void termThread() {
		logMessage("termThread");

		terminate();

		super.termThread();
	}
	
	/**
	 * Terminate all variables
	 */
	private void terminate() {
		logMessage("terminate");
		
		try {
			recycle(m_session);

			if (this.mq != null) {
				this.mq.close(0);
				this.mq = null;
			}
			
			if (dominoTaskID != 0) AddInDeleteStatusLine(dominoTaskID);
			
			logMessage("UNLOADED (OK)");
		} catch (NotesException e) {
			logMessage("UNLOADED (**FAILED**)");
		}
	}
}