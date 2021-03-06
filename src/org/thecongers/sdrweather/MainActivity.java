package org.thecongers.sdrweather;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.security.MessageDigest;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
// For debugging
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.stericson.RootTools.*;

public class MainActivity extends Activity {
    RtlTask nativeTask;
    // For debugging
    private static final String TAG = "SDRWeather";
    private Cursor events;
    private Cursor fips;
    private Cursor clc;
    private EventDatabase eventdb;
    private FipsDatabase fipsdb;
    private ClcDatabase clcdb;
    EasDatabase easdb;
    private Spinner freqSpinner;
    private SharedPreferences sharedPrefs;
    private static final int SETTINGS_RESULT = 1;
    private String dataRoot;
    boolean stopAudioRead = false;
    boolean dongleUnplugged = false;
    AudioTrack audioTrack;
    Thread audioThread;
    Button startButton;
    Button stopButton;
    Switch audioSwitch;
    WebView activeEventsView;
    TextView evLvlText;
    TextView evDescText;
    TextView regionsText;
    TextView orgText;
    TextView purgeTimeText;
    TextView issueTimeText;
    TextView callsignText;
    int minBuffSize;
    // Controls the look of events in the app
    String eventLook = "<!DOCTYPE html><html><head><style>" + 
    		"div.box{padding:5px;border:3px solid gray;margin:0px;font-size:x-small;}" + 
    		"div.level{padding:5px;border:3px solid gray;margin:0px;font-size:small;}" + 
    		"</style></head><body>";

    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        eventdb = new EventDatabase(this);
        fipsdb = new FipsDatabase(this);
        clcdb = new ClcDatabase(this);
        easdb = new EasDatabase(this);
        
        // Purge old events from database
        easdb.purgeExpiredMsg();
        
        // Audio switch setup
        audioSwitch = (Switch) findViewById(R.id.switch1);
        audioSwitch.setOnCheckedChangeListener(new OnCheckedChangeListener() {
        	@Override
        	public void onCheckedChanged(CompoundButton buttonView,
        	boolean isChecked) {
        		if ( audioTrack != null ) {
        			if (isChecked) {
        				//Log.d(TAG, "Audio is set to on" );
        				audioTrack.setStereoVolume(1.0f, 1.0f);
        			} else {
        				//Log.d(TAG, "Audio is set to off" );
        				audioTrack.setStereoVolume(0.0f, 0.0f);
        			}
        		}
        		}
        	});
        
        // Set initial audio switch from preferences
        if (sharedPrefs.getBoolean("prefStartAudio", true)) {
        	audioSwitch.setChecked(true);
        } else {
        	audioSwitch.setChecked(false);
        }
        
        activeEventsView = (WebView) findViewById(R.id.webView1); 
        activeEventsView.loadData("<html><body>No active events</body></html>", "text/html", null);
        
        startButton = (Button) findViewById(R.id.button1);
        stopButton = (Button) findViewById(R.id.button2);
        // Disable stop button
    	stopButton.setEnabled(false);
        
        // Set initial frequency from preferences
    	freqSpinner = (Spinner) findViewById(R.id.spinner1);
        int freq = Integer.parseInt(sharedPrefs.getString("prefDefaultFreq", "6"));
        freqSpinner.setSelection(freq);
        
        // Show last currently active event if available
        Cursor easmsg = easdb.getActiveEvent();  
        Log.d(TAG, "Lookup active events");
	    if( easmsg != null && easmsg.moveToFirst() ){
	    	StringBuilder htmlText = new StringBuilder();
	    	htmlText.append(eventLook);
	    	while (easmsg.isAfterLast() == false) 
	    	{
	    		Log.d(TAG, "Found an event!");
	    		String color = null;
	    		String level = easmsg.getString(easmsg.getColumnIndex("level"));
	    		String desc = easmsg.getString(easmsg.getColumnIndex("desc"));
	    		String regions = easmsg.getString(easmsg.getColumnIndex("regions"));
	    		String org = easmsg.getString(easmsg.getColumnIndex("org"));
	    		String purgetime = easmsg.getString(easmsg.getColumnIndex("purgetime"));
	    		String timeissued = easmsg.getString(easmsg.getColumnIndex("timeissued"));
	    		String callsign = easmsg.getString(easmsg.getColumnIndex("callsign"));
	    		if("Test".equals(level)){
					color = "WHITE";
				}else if("Warning".equals(level)){
					color = "RED";
				}else if("Watch".equals(level)){
					color = "YELLOW";
				}else if("Advisory".equals(level)){
					color = "GREEN";
				}
	    		htmlText.append("<p><div class=\"box\"><div class=\"level\" style=\"background-color:" + color + ";\"><b>" + level + "</b>" + "</div>" + desc + 
	    				"<br /><b>Time issued: </b>" + timeissued + " UTC<br /><b>Expires at: </b>" + 
	    				purgetime + " UTC<br /><b>Regions affected: </b>" + regions + "<br /><b>Originator: </b>" 
	    				+ org + "<br /><b>Callsign: </b>" + callsign + "<br /></div></p>");
	    		easmsg.moveToNext();
	    	}
	    	htmlText.append("</body></html>");
	    	activeEventsView.loadData(htmlText.toString(), "text/html", null);
	    }
        
        // Get data root
        dataRoot = getApplicationContext().getFilesDir().getParentFile().getPath();
        String binDir = dataRoot + "/nativeFolder/";
    	// Create directory for binaries
    	File nativeDirectory = new File(binDir);
    	nativeDirectory.mkdirs();
    	// Copy binary assets
    	copyFile("nativeFolder/eas-test.raw",dataRoot + "/nativeFolder/eas-test.raw",getBaseContext());
    	copyFile("nativeFolder/multimon-ng",dataRoot + "/nativeFolder/multimon-ng",getBaseContext());
    	copyFile("nativeFolder/rtl_fm",dataRoot + "/nativeFolder/rtl_fm",getBaseContext());
    	// Set execute permissions
        StringBuilder command = new StringBuilder("chmod 700 ");
        command.append(dataRoot + "/nativeFolder/multimon-ng");
        StringBuilder command2 = new StringBuilder("chmod 700 ");
        command2.append(dataRoot + "/nativeFolder/rtl_fm");
        // Create named pipe for audio
        StringBuilder command3 = new StringBuilder("mkfifo ");
        command3.append(dataRoot + "/pipe");
        try {
			Runtime.getRuntime().exec(command.toString());
			Runtime.getRuntime().exec(command2.toString());
			Runtime.getRuntime().exec(command3.toString());
		} catch (IOException e) {
		}
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        // Update display with latest active event in the database
        easdb = new EasDatabase(this);
        Cursor easmsg = easdb.getActiveEvent();
        
        activeEventsView.loadData("<html></html>", "text/html", null);
        activeEventsView.loadData("<html><body>No active events</body></html>", "text/html", null);
        
        Log.d(TAG, "Lookup active events");
        if( easmsg != null && easmsg.moveToFirst() ){
	    	StringBuilder htmlText = new StringBuilder();
	    	htmlText.append(eventLook);
	    	while (easmsg.isAfterLast() == false) 
	    	{
	    		Log.d(TAG, "Found an event!");
	    		String color = null;
	    		String level = easmsg.getString(easmsg.getColumnIndex("level"));
	    		String desc = easmsg.getString(easmsg.getColumnIndex("desc"));
	    		String regions = easmsg.getString(easmsg.getColumnIndex("regions"));
	    		String org = easmsg.getString(easmsg.getColumnIndex("org"));
	    		String purgetime = easmsg.getString(easmsg.getColumnIndex("purgetime"));
	    		String timeissued = easmsg.getString(easmsg.getColumnIndex("timeissued"));
	    		String callsign = easmsg.getString(easmsg.getColumnIndex("callsign"));
	    		if("Test".equals(level)){
					color = "WHITE";
				}else if("Warning".equals(level)){
					color = "RED";
				}else if("Watch".equals(level)){
					color = "YELLOW";
				}else if("Advisory".equals(level)){
					color = "GREEN";
				}
	    		htmlText.append("<p><div class=\"box\"><div class=\"level\" style=\"background-color:" + color + ";\"><b>" + level + "</b>" + "</div>" + desc + 
	    				"<br /><b>Time issued: </b>" + timeissued + " UTC<br /><b>Expires at: </b>" + 
	    				purgetime + " UTC<br /><b>Regions affected: </b>" + regions + "<br /><b>Originator: </b>" 
	    				+ org + "<br /><b>Callsign: </b>" + callsign + "<br /></div></p>");
	    		easmsg.moveToNext();
	    	}
	    	htmlText.append("</body></html>");
	    	activeEventsView.loadData(htmlText.toString(), "text/html", null);
	    }
    }

    /*
    @Override
    protected void onPause() {
        super.onPause();
        mTask.stop();
        
    }
    */

	// Start button press
    public void onClickStart(View view)
    {
    	Log.d(TAG, "Start Pressed" );
    	dongleUnplugged = false;
    	if (RootTools.isRootAvailable() && RootTools.isBusyboxAvailable()) {
    		// Get Frequency and gain from preferences
    		String freq = String.valueOf(freqSpinner.getSelectedItem());
    		String gain = sharedPrefs.getString("prefGain", "42");
    		String squelch = sharedPrefs.getString("prefSqel", "0");
    		// Call for process to start
    		nativeTask = new RtlTask();
    		nativeTask.execute(freq,gain,squelch);
    		// Start audio
    		audioStart();
    		// Check for mute status and set
    		if (audioSwitch.isChecked()) {
    			audioTrack.setStereoVolume(1.0f, 1.0f);
    		} else {
    			audioTrack.setStereoVolume(0.0f, 0.0f);
    		}
    		// Disable start button
    		startButton.setEnabled(false);
    		// Enable stop button
        	stopButton.setEnabled(true);
        	// Disable frequency spinner
        	freqSpinner.setEnabled(false);
    	} else {
    		// Display message about lack of root and or busybox
    		if (!RootTools.isRootAvailable()) {
    			Toast.makeText(MainActivity.this,
    					"Root Access Not Available!",
    					Toast.LENGTH_LONG).show();
    		} else if (!RootTools.isBusyboxAvailable()) {
    			// Offer busybox if not installed
    			RootTools.offerBusyBox(MainActivity.this);   			
    		}
    	}
    }
    
    // Stop button press
    public void onClickStop(View view)
    {
    	Log.d(TAG, "Stop Pressed" );
    	audioStop();
    	nativeTask.stop();
    	// Enable start button
    	startButton.setEnabled(true);
    	// Disable stop button
    	stopButton.setEnabled(false);
    	// Enable frequency spinner
    	freqSpinner.setEnabled(true);
    }
    
    Runnable m_audioGenerator = new Runnable()
    {       
        public void run()
        {
            Thread.currentThread().setPriority(Thread.MIN_PRIORITY);
            
            FileInputStream audioStream = null;
            try {
                audioStream = new FileInputStream(dataRoot + "/pipe");
            } catch (FileNotFoundException e) {
            	Log.d(TAG, "Named Pipe Not Found" );
            }
            byte [] audioData = new byte[minBuffSize/2]; 
            Log.d(TAG, "Read audio from named pipe" );
            while(!stopAudioRead) {
            	try {
					audioStream.read(audioData, 0, audioData.length);
				} catch (IOException e) {
					e.printStackTrace();
				}
            	audioTrack.write(audioData, 0, audioData.length); 
            }
        }
    };

    // Start audio
    void audioStart()
    {
    	stopAudioRead = false;
        // Get buffer size
        minBuffSize = AudioTrack.getMinBufferSize(22050, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        
        // Setup AudioTrack settings
        audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, 22050, AudioFormat.CHANNEL_OUT_MONO,
                                        AudioFormat.ENCODING_PCM_16BIT, minBuffSize, AudioTrack.MODE_STREAM);
        audioTrack.play();
        audioThread = new Thread(m_audioGenerator);
        audioThread.start();
    }

    // Stop audio
    void audioStop()
    {
    	stopAudioRead = true;
        audioTrack.stop();
    }   

    // Draw options menu
    @Override
	public boolean onCreateOptionsMenu(Menu menu)
    {
		// Inflate the menu
		getMenuInflater().inflate(R.menu.main, menu);
		return true;
	}
    
    // When settings menu is selected
    @Override
    public boolean onOptionsItemSelected(MenuItem item)
    {
    	switch (item.getItemId()) {
            case R.id.action_settings:
                // Settings Menu was selected
            	Log.d(TAG, "Settings Menu was selected");
            	Intent i = new Intent(getApplicationContext(), UserSettingActivity.class);
                startActivityForResult(i, SETTINGS_RESULT);
                return true;
            case R.id.action_about:
                // About was selected
            	Log.d(TAG, "About was selected");
            	AlertDialog.Builder builder = new AlertDialog.Builder(this);
            	builder.setTitle("About");
            	builder.setMessage(readRawTextFile(this, R.raw.about));
            	builder.setPositiveButton("OK", null);
            	@SuppressWarnings("unused")
				AlertDialog dialog = builder.show();
                return true;
            default:
                return super.onOptionsItemSelected(item);
    	}
    }
    
    //Runs when settings are updated
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data)
    {
    	super.onActivityResult(requestCode, resultCode, data);
        if(requestCode==SETTINGS_RESULT)
        {
        	updateUserSettings();
        }
    }
    
    // Update UI when settings are updated
    private void updateUserSettings() 
    {
    	// Update frequency selector
    	int freq = Integer.parseInt(sharedPrefs.getString("prefDefaultFreq", "6"));
    	freqSpinner.setSelection(freq);
     }
    
    // Send Notification
    private void Notify(String notificationTitle, String notificationMessage, int notificationID) 
    {
    	  NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    	  Intent notificationIntent = new Intent(this, MainActivity.class);
    	  PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
    	    	    notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT);
    	  // Build notification
    	  Notification.Builder builder = new Notification.Builder(this);
    	  builder.setContentTitle(notificationTitle)
          	.setContentText(notificationMessage)
          	.setSmallIcon(R.drawable.app_icon)
          	.setContentIntent(pendingIntent);
    	  Notification notification = builder.build();  
    	  // Hide notification after its been selected
    	  notification.flags |= Notification.FLAG_AUTO_CANCEL;
    	  // Send notification
    	  notificationManager.notify(notificationID, notification);
    }
 
    class RtlTask extends AsyncTask<String, Void, Void> {
        PipedOutputStream mPOut;
        PipedInputStream mPIn;
        LineNumberReader mReader;
        Process mProcess;

        @Override
        protected void onPreExecute() {
            mPOut = new PipedOutputStream();
            try {
                mPIn = new PipedInputStream(mPOut);
                mReader = new LineNumberReader(new InputStreamReader(mPIn));
            } catch (IOException e) {
                cancel(true);
            }

        }

        // Stop the process
        public void stop() {
            Process p = mProcess;
            if (p != null) {
                p.destroy();
            }
            cancel(true);
            // Kill process fail safe
            StringBuilder command1 = new StringBuilder("/system/xbin/su -c killall -9 ");
            command1.append("rtl_fm multimon-ng");
            try {
    			Runtime.getRuntime().exec(command1.toString());
    		} catch (IOException e) {
    		}
        }

        @Override
        protected Void doInBackground(String... params) {
            try {
            	Log.d(TAG, "Executing native code");
            	// Build command and execute
            	String dataRoot = getApplicationContext().getFilesDir().getParentFile().getPath();
            	//Test wave
            	//String[] cmd = { "/system/xbin/su", "-c", "cat " + dataRoot + "/nativeFolder/eas-test.raw | tee " + dataRoot + "/pipe | " + dataRoot + "/nativeFolder/multimon-ng -a EAS -q -t raw -" };
            	// No audio just alert monitoring
            	//String[] cmd = { "/system/xbin/su", "-c", dataRoot + "/nativeFolder/rtl_fm -f " + params[0] + "M -s 22050 -g " + params[1]  + " -l " + params[2] + " | " + dataRoot + "/nativeFolder/multimon-ng -a EAS -q -t raw -" };
            	// Audio and alert monitoring
            	String[] cmd = { "/system/xbin/su", "-c", dataRoot + "/nativeFolder/rtl_fm -f " + params[0] + "M -s 22050 -g " + params[1] + " -l " + params[2] + " | tee " + dataRoot + "/pipe | " + dataRoot + "/nativeFolder/multimon-ng -a EAS -q -t raw -" };
                mProcess = new ProcessBuilder()
                    .command(cmd)
                    .redirectErrorStream(true)
                    .start();
                
                try {
                    InputStream in = mProcess.getInputStream();
                    OutputStream out = mProcess.getOutputStream();
                    byte[] buffer = new byte[1024];
                    int count;

                    // in -> buffer -> mPOut -> mReader -> 1 line of information to parse
                    while ((count = in.read(buffer)) != -1) {
                        mPOut.write(buffer, 0, count);
                        publishProgress();
                    }
                    out.close();
                    in.close();
                    mPOut.close();
                    mPIn.close();
                    
                } finally {
                    mProcess.destroy();
                    mProcess = null;
                }
            } catch (IOException e) {
            }
            return null;
        }
        @SuppressLint("SimpleDateFormat")
		@Override
        protected void onProgressUpdate(Void... values) {
            try {
                while (mReader.ready()) {
                	String currentLine = mReader.readLine();
                    // Check for alert
                    if (currentLine.contains("EAS:")) {
                    	// Log command output
                        Log.d(TAG, "Output: " + currentLine + "\n");
                        
        				Log.d(TAG, "Found EAS Alert, parsing.....");
        				
        				// Unumute audio if configured
        		        if (sharedPrefs.getBoolean("prefStartAudio", true)) {
        		        	// Update audio switch
        		        	audioSwitch.setChecked(true);
        		        	// Unmute Audio
        		        	audioTrack.setStereoVolume(1.0f, 1.0f);
        		        }
        		        
        				String org = null;
        				String eventlevel = null;
        				String eventdesc = null;
        				String callSign = null;
        				int notificationID = 0;
        				
        				// Start parsing message into fields
        				String [] rawEASMsg = currentLine.split(":");
        				rawEASMsg[1] = rawEASMsg[1].trim();
        				String [] easMsg = rawEASMsg[1].split("-");
        				int size = easMsg.length;
        				Log.d(TAG, "# of fields: " + size);
        				
        				//Check to see if message has minimum number of fields
        				if (size > 5 ) {
        					/*
        					 * Information from: http://en.wikipedia.org/wiki/Specific_Area_Message_Encoding
        					 * 
        					 * ORG � Originator code; programmed per unit when put into operation:
        					 * * PEP � Primary Entry Point Station; President or other authorized national officials
        					 * * CIV � Civil authorities; i.e. Governor, state/local emergency management, local police/fire officials
        					 * * WXR � National Weather Service (or Environment Canada.); Any weather-related alert
        					 * * EAS � EAS Participant; Broadcasters. Generally only used with test messages.
        					 */
        					org = easMsg[1];
        					Log.d(TAG, "Originator Code: " + org);
        					
        					/*
        					 * EEE � Event code; programmed at time of event
        					 */
        					String eee = easMsg[2];
        					Log.d(TAG, "Event Code: " + eee);
        					//Look up event code in database, return level and description
        					events = eventdb.getEventInfo(eee);
        					String color = null;
        					if( events != null && events.moveToFirst() ){
        						eventlevel = events.getString(events.getColumnIndex("eventlevel"));
        						if("Test".equals(eventlevel)){
        							color = "WHITE";
        						}else if("Warning".equals(eventlevel)){
        							color = "RED";
        						}else if("Watch".equals(eventlevel)){
        							color = "YELLOW";
        						}else if("Advisory".equals(eventlevel)){
        							color = "GREEN";
        						}
        						eventdesc = events.getString(events.getColumnIndex("eventdesc"));
        					}
        					
        					/*
        					 * PSSCCC � Location codes (up to 31 location codes per message), each beginning with a dash character; 
        					 * programmed at time of event In the United States, the first digit (P) is zero if the entire county or area 
        					 * is included in the warning, otherwise, it is a non-zero number depending on the location of the emergency. 
        					 * In the United States, the remaining five digits are the FIPS state code (SS) and FIPS county code (CCC). 
        					 * The entire state may be specified by using county number 000 (three zeros). In Canada, all six digits specify 
        					 * the Canadian Location Code, which corresponds to a specific forecast region as used by the Meteorological 
        					 * Service of Canada. All forecast region numbers are six digits with the first digit always zero.
        					 */
        					String [] temp = easMsg[size - 3].split("\\+");
        					easMsg[size - 3] = temp[0];
        					int j=0;
        					String [] locationCodes = new String[size - 5];

        					StringBuilder regions = new StringBuilder("");
        					
        					// Get country from preferences and look up region codes from appropriate database
        					int country = Integer.parseInt(sharedPrefs.getString("prefDefaultCountry", "0"));
        					if( country == 0 ){ // United States
        						for (int i=3; i < size - 2; i++) {
        							locationCodes[j] = easMsg[i];
        							Log.d(TAG, "Location Code: " + locationCodes[j]);
        							
        							// Look up fips code in database, return county and state
        							String fipscode = locationCodes[j].substring(1, 6);
        							Log.d(TAG, "Looking up county/state for fips code: " + fipscode);
        							fips = fipsdb.getCountyState(fipscode);
        							if( fips != null && fips.moveToFirst() ){
        								Log.d(TAG, "Location: " + fips.getString(fips.getColumnIndex("county")) + ", " + fips.getString(fips.getColumnIndex("state")));
        								regions.append(fips.getString(fips.getColumnIndex("county")) + "," + fips.getString(fips.getColumnIndex("state")) + ";");
        							}
        							j++;
        						}
        					}else if( country == 1 ){ // Canada
        						for (int i=3; i < size - 2; i++) {
        							locationCodes[j] = easMsg[i];
        							Log.d(TAG, "Location Code: " + locationCodes[j]);

        							// Look up clc code in database, return region and province/territory
        							String clccode = locationCodes[j].substring(1, 6);
        							Log.d(TAG, "Looking up region and province/territory information for clc code: " + clccode);
        							clc = clcdb.getCountyState(clccode);
        							if( clc != null && clc.moveToFirst() ){
        								Log.d(TAG, "Location: " + clc.getString(clc.getColumnIndex("region")) + ", " + clc.getString(clc.getColumnIndex("provinceterritory")));
        								regions.append(clc.getString(clc.getColumnIndex("region")) + "," + clc.getString(clc.getColumnIndex("provinceterritory")) + ";");
        							}
        							j++;
        						}
        					}
        					/*
        					 * TTTT � In the format hhmm, using 15 minute increments up to one hour, using 30 minute increments up to six hours,
        					 * and using hourly increments beyond six hours. Weekly and monthly tests sometimes have a 12 hour or greater
        					 * purge time to assure users have an ample opportunity to verify reception of the test event messages; 
        					 * however; 15 minutes is more common, especially on NOAA Weather Radio's tests.
        					 */
        					String purgeTime = temp[1];
        					Log.d(TAG, "Purge time: " + purgeTime);
        					String purgeTimeHour = purgeTime.substring(0,2);
        					String purgeTimeMin = purgeTime.substring(2,4);
        				
        					/*
        					 * JJJHHMM � Exact time of issue, in UTC, (without time zone adjustments).
        					 * JJJ is the Ordinal date (day) of the year, with leading zeros
        					 * HHMM is the hours and minutes (24-hour format), in UTC, with leading zeros
        					 */
        					String timeOfIssue = easMsg[size - 2];
        				
        					// Parse and convert date and time
        					String jjj = timeOfIssue.substring(0, 3);
        					String hh = timeOfIssue.substring(3, 5);
        					String mm = timeOfIssue.substring(5, 7);
        					int day = Integer.parseInt(jjj);
        					int year = Calendar.getInstance().get(Calendar.YEAR);
        					String convDate = formatOrdinal(year, day);
        				
        					// Convert to a more readable format
        					SimpleDateFormat utcFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        					utcFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        					Date date = utcFormat.parse(convDate + "T" + hh + ":" + mm + ":00.000Z");
        					SimpleDateFormat defaultFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS");
        					defaultFormat.setTimeZone(TimeZone.getDefault());
        					
        					// Get current date/time
        					Calendar cal = Calendar.getInstance();
        					Date date2 = cal.getTime();
        					SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        					formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        					String curdate = formatter.format(date2);
        					Log.d(TAG, "Message Received at (UTC): " + curdate);
        					
        					// Calculate EAS event expiration date/time
        					cal.clear();
        					cal.setTime(date);
        					Date idate = cal.getTime();
        					formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        					String issuedate = formatter.format(idate);
        					Log.d(TAG, "Time of issue (UTC): " + issuedate);
        					
        					int exphours = Integer.parseInt(purgeTimeHour);
        					int expmins = Integer.parseInt(purgeTimeMin);
        					cal.add(Calendar.HOUR_OF_DAY, exphours);
        					cal.add(Calendar.MINUTE, expmins);
        					Date edate = cal.getTime();
        					formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        					String expdate = formatter.format(edate);
        					Log.d(TAG, "Expires at (UTC): " + expdate);
        				
        					/*
        					 * LLLLLLLL � Eight-character station callsign identification, with "/" used instead of "�" (such as the first eight
        					 * letters of a cable headend's location, WABC/FM for WABC-FM, or KLOX/NWS for a weather radio station
        					 * programmed from Los Angeles).
        					 */
        					callSign = easMsg[size - 1];
        					Log.d(TAG, "Call Sign: " + callSign);
        					
        					//Update EAS event database
        					easdb.addEasMsg (new EasMsg(org, eventdesc, eventlevel, curdate, issuedate, callSign, expdate, regions.toString(), sharedPrefs.getString("prefDefaultCountry", "0")));
        					
        					// Update active events list in app
        					Cursor easmsg = easdb.getActiveEvent();
        			        Log.d(TAG, "Lookup active events");
        				    if( easmsg != null && easmsg.moveToFirst() ){
        				    	StringBuilder
        				    	htmlText = new StringBuilder();
        				    	htmlText.append(eventLook);
        				    	while (easmsg.isAfterLast() == false) 
        				    	{
        				    		Log.d(TAG, "Found an event!");
        				    		color = null;
        				    		String level = easmsg.getString(easmsg.getColumnIndex("level"));
        				    		String desc = easmsg.getString(easmsg.getColumnIndex("desc"));
        				    		String evregions = easmsg.getString(easmsg.getColumnIndex("regions"));
        				    		org = easmsg.getString(easmsg.getColumnIndex("org"));
        				    		String purgetime = easmsg.getString(easmsg.getColumnIndex("purgetime"));
        				    		String timeissued = easmsg.getString(easmsg.getColumnIndex("timeissued"));
        				    		String callsign = easmsg.getString(easmsg.getColumnIndex("callsign"));
        				    		if("Test".equals(level)){
        								color = "WHITE";
        							}else if("Warning".equals(level)){
        								color = "RED";
        							}else if("Watch".equals(level)){
        								color = "YELLOW";
        							}else if("Advisory".equals(level)){
        								color = "GREEN";
        							}
        				    		htmlText.append("<p><div class=\"box\"><div class=\"level\" style=\"background-color:" + color + ";\"><b>" + level + "</b>" + "</div>" + 
        				    				desc + "<br /><b>Time issued: </b>" + timeissued + " UTC<br /><b>Expires at: </b>" + purgetime + " UTC<br /><b>Regions affected: </b>" + 
        				    				evregions + "<br /><b>Originator: </b>" + org + "<br /><b>Callsign: </b>" + callsign + "<br /></div></p>");
        				    		easmsg.moveToNext();
        				    	}
        				    	htmlText.append("</body></html>");
        				    	activeEventsView.loadData(htmlText.toString(), "text/html", null);
        				    	
        				    }
        				    
        					// Send a notification
    						notificationID = 1;
    						Log.d(TAG, "notificationID: " + notificationID);
        					Notify(eventlevel + " from " + callSign,
        							eventdesc + " was issued", notificationID);
        					
        					// Tell widget to update
        					SdrWidgetProvider.updateWidgetContent(getBaseContext(), 
        					    AppWidgetManager.getInstance(getBaseContext()));
        				
        					}
        		    } else if (currentLine.contains("No supported devices found")) {
        		    	// Log command output
                        Log.d(TAG, "Output: " + currentLine);
                        // Send toast
        		    	Toast.makeText(MainActivity.this,
            					"No supported device found!",
            					Toast.LENGTH_LONG).show();
        		    	stopButton.performClick();
        		    } else if (currentLine.contains("cb transfer status: 5, canceling")) {
        		    	// Device disconnected
        		    	// Log command output
                        Log.d(TAG, "Output: " + currentLine);
        		    	// Stop native processes and audio
        		    	if (dongleUnplugged == false) {
        		    		Log.d(TAG, "Device disconnected");
        		    		stopButton.performClick();
        		    		dongleUnplugged = true;
        		    	}
        		    	
        		    } else if (currentLine.contains("warning: noninteger number of samples read")) {
        		    	// Drop this message
        		    } else {
                    	// Log command output
                        Log.d(TAG, "Output: " + currentLine);
        		    }
        		    
                    
                }
            } catch (IOException t) {
            } catch (ParseException e) {
				e.printStackTrace();
			}
        }
    }
    
    // File copy function
 	private static void copyFile(String assetPath, String localPath, Context context) {
 	    try {
 	    	// Check if file exists
 	    	File existFile = new File(localPath);
 	    	if(existFile.exists()) {
 	    		// Get existing filse md5sum for comparison
 	    		String existingFileMD5 = fileToMD5(localPath);
 	    		
 	    		// Copy file to a temp location
 	    		InputStream in = context.getAssets().open(assetPath);
 	    		FileOutputStream out = new FileOutputStream(localPath + "-tmp");
 	    		int read;
 	    		byte[] buffer = new byte[4096];
 	    		while ((read = in.read(buffer)) > 0) {
 	    			out.write(buffer, 0, read);
 	    		}
 	    		out.close();
 	    		in.close();
 	    		
 	    		// Get package files md5sum for comparison
 	    		String packagedFileMD5 = fileToMD5(localPath + "-tmp");
 	    		
 	    		// Compare md5sums
 	    		if ( existingFileMD5.equals(packagedFileMD5) ) {
 	    			// Files are identical
 	    			// Delete temp file
 	    			File file = new File(localPath + "-tmp");
 	    			file.delete();
 	    		} else {
 	    			// Copy packaged version over installed version
 	    			InputStream in2 = new FileInputStream(localPath + "-tmp");
 	    			FileOutputStream out2 = new FileOutputStream(localPath);
 	    			int read2;
 	    			byte[] buffer2 = new byte[4096];
 	    			while ((read2 = in2.read(buffer2)) > 0) {
 	    				out2.write(buffer2, 0, read2);
 	    			}
 	    			out2.close();
 	    			in2.close();
 	    			Log.d(TAG, "File " + assetPath + " updated!");
 	    		}
 	    	} else {
 	    		// File doesn't exist so go ahead and copy it
 	    		InputStream in = context.getAssets().open(assetPath);
 	    		FileOutputStream out = new FileOutputStream(localPath);
 	    		int read;
 	    		byte[] buffer = new byte[4096];
 	    		while ((read = in.read(buffer)) > 0) {
 	    			out.write(buffer, 0, read);
 	    		}
 	    		out.close();
 	    		in.close();
 	    		Log.d(TAG, "File " + assetPath + " copied successfully!");
 	    		}

 	    } catch (IOException e) {
 	        throw new RuntimeException(e);
 	    }
 	}
 	
 	// Convert ordinal date format to simple
	@SuppressLint("SimpleDateFormat")
	static String formatOrdinal(int year, int day) {
		  Calendar cal = Calendar.getInstance();
		  cal.clear();
		  cal.set(Calendar.YEAR, year);
		  cal.set(Calendar.DAY_OF_YEAR, day);
		  Date date = cal.getTime();
		  SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd");
		  return formatter.format(date);
	}
	
	// Get a files md5sum
	public static String fileToMD5(String filePath) {
	    InputStream inputStream = null;
	    try {
	        inputStream = new FileInputStream(filePath);
	        byte[] buffer = new byte[1024];
	        MessageDigest digest = MessageDigest.getInstance("MD5");
	        int numRead = 0;
	        while (numRead != -1) {
	            numRead = inputStream.read(buffer);
	            if (numRead > 0)
	                digest.update(buffer, 0, numRead);
	        }
	        byte [] md5Bytes = digest.digest();
	        return convertHashToString(md5Bytes);
	    } catch (Exception e) {
	        return null;
	    } finally {
	        if (inputStream != null) {
	            try {
	                inputStream.close();
	            } catch (Exception e) { }
	        }
	    }
	}

	// Convert md5 hash to string
	@SuppressLint("DefaultLocale")
	private static String convertHashToString(byte[] md5Bytes) {
	    String returnVal = "";
	    for (int i = 0; i < md5Bytes.length; i++) {
	        returnVal += Integer.toString(( md5Bytes[i] & 0xff ) + 0x100, 16).substring(1);
	    }
	    return returnVal.toUpperCase(Locale.US);
	}
	
	// Read raw text file
	public static String readRawTextFile(Context ctx, int resId)
	{
	    InputStream inputStream = ctx.getResources().openRawResource(resId);

	    InputStreamReader inputreader = new InputStreamReader(inputStream);
	    BufferedReader buffreader = new BufferedReader(inputreader);
	    String line;
	    StringBuilder text = new StringBuilder();

	    try {
	        while (( line = buffreader.readLine()) != null) {
	            text.append(line);
	            text.append('\n');
	        }
	    } catch (IOException e) {
	        return null;
	    }
	    return text.toString();
	}
}