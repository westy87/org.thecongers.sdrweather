package org.thecongers.sdrweather;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;

import android.os.AsyncTask;
import android.os.Bundle;
import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.stericson.RootTools.*;

public class MainActivity extends Activity {
    RtlTask mTask;
    private static final String TAG = "SDRWeather";
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // String dataRoot = getAppContext().getFilesDir().getParentFile().getPath();
        String dataRoot = "/data/data/org.thecongers.sdrweather";
        String binDir = dataRoot + "/nativeFolder/";
    	// Create directory for binaries
    	File nativeDirectory = new File(binDir);
    	nativeDirectory.mkdirs();
    	// Copy binaries
    	copyFile("nativeFolder/test",dataRoot + "/nativeFolder/multimon",getBaseContext());
    	copyFile("nativeFolder/rtl_fm",dataRoot + "/nativeFolder/rtl_fm",getBaseContext());
    	// Set execute permissions
        StringBuilder command = new StringBuilder("chmod 700 ");
        command.append(dataRoot + "/nativeFolder/multimon");
        try {
			Runtime.getRuntime().exec(command.toString());
		} catch (IOException e3) {
			// TODO Auto-generated catch block
			e3.printStackTrace();
		}
        // Set execute permissions
        StringBuilder command2 = new StringBuilder("chmod 700 ");
        command2.append(dataRoot + "/nativeFolder/rtl_fm");
        try {
			Runtime.getRuntime().exec(command2.toString());
		} catch (IOException e3) {
			// TODO Auto-generated catch block
			e3.printStackTrace();
		}
    }

    public void onClickStart(View view) {
    	if (RootTools.isRootAvailable()) {
    		mTask = new RtlTask();
    		mTask.execute();
    	} else {
    	    // TODO Handle no root
    	}
    }
    public void onClickStop(View view) {
    	mTask.stop();
    }
    /*
    @Override
    protected void onResume() {
        super.onResume();
        mTask = new RtlTask();
        mTask.execute();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mTask.stop();
        
    }
	*/
    class RtlTask extends AsyncTask<String, Void, Void> {
        PipedOutputStream mPOut;
        PipedInputStream mPIn;
        LineNumberReader mReader;
        Process mProcess;
        TextView mText = (TextView) findViewById(R.id.TextView02);
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

        public void stop() {
            Process p = mProcess;
            if (p != null) {
                p.destroy();
            }
            cancel(true);
        }

        @Override
        protected Void doInBackground(String... params) {
            try {
		Log.d(TAG, "Excuting command");
		//String[] cmd = { "/system/xbin/su", "-c", "/data/data/org.thecongers.sdrweather/nativeFolder/rtl_fm -N -f 162.546M -s 22.5k -g 50 | /data/data/org.thecongers.sdrweather/nativeFolder/multimon -a EAS -q -t raw -" };
		String[] cmd = { "/system/xbin/su", "-c", "/data/data/org.thecongers.sdrweather/nativeFolder/multimon" };
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
        @Override
        protected void onProgressUpdate(Void... values) {
            try {
                // Is a line ready to read from the command?
                while (mReader.ready()) {
                	String currentLine = mReader.readLine();
                	// Display command output
                    mText.append(currentLine + "\n");
                    Log.d(TAG, "Output: " + currentLine);
                    if (currentLine.contains("EAS:")) {
        				Log.d(TAG, "Found EAS Alert, parsing.....");
        				// Start parsing message
        				String [] rawEASMsg = currentLine.split(":");
        				rawEASMsg[1] = rawEASMsg[1].trim();
        				String [] easMsg = rawEASMsg[1].split("-");
        				int size = easMsg.length;
        				Log.d(TAG, "# of fields: " + size);
        				/*
        				 * Information from: http://en.wikipedia.org/wiki/Specific_Area_Message_Encoding
        				 * 
        				 * ORG � Originator code; programmed per unit when put into operation:
        				 * * PEP � Primary Entry Point Station; President or other authorized national officials
        				 * * CIV � Civil authorities; i.e. Governor, state/local emergency management, local police/fire officials
        				 * * WXR � National Weather Service (or Environment Canada.); Any weather-related alert
        				 * * EAS � EAS Participant; Broadcasters. Generally only used with test messages.
        				 */
        				String org = easMsg[1];
        				Log.d(TAG, "Originator Code: " + org);
        				/*
        				 * EEE � Event code; programmed at time of event
        				 */
        				String eee = easMsg[2];
        				Log.d(TAG, "Event Code: " + eee);
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
        				for (int i=3; i < size - 2; i++) {
        					locationCodes[j] = easMsg[i];
        					Log.d(TAG, "Location Code: " + locationCodes[j]);
        					j++;
        				}
        				/*
        				 * TTTT � In the format hhmm, using 15 minute increments up to one hour, using 30 minute increments up to six hours,
        				 * and using hourly increments beyond six hours. Weekly and monthly tests sometimes have a 12 hour or greater
        				 * purge time to assure users have an ample opportunity to verify reception of the test event messages; 
        				 * however; 15 minutes is more common, especially on NOAA Weather Radio's tests.
        				 */
        				String purgeTime = temp[1];
        				Log.d(TAG, "Purge time: " + purgeTime);
        				/*
        				 * JJJHHMM � Exact time of issue, in UTC, (without time zone adjustments).
        				 * JJJ is the Ordinal date (day) of the year, with leading zeros
        				 * HHMM is the hours and minutes (24-hour format), in UTC, with leading zeros
        				 */
        				String timeOfIssue = easMsg[size - 2];
        				Log.d(TAG, "Time of issue: " + timeOfIssue);
        				/*
        				 * LLLLLLLL � Eight-character station callsign identification, with "/" used instead of "�" (such as the first eight
        				 * letters of a cable headend's location, WABC/FM for WABC-FM, or KLOX/NWS for a weather radio station
        				 * programmed from Los Angeles).
        				 */
        				String callSign = easMsg[size - 1];
        				Log.d(TAG, "Call Sign: " + callSign);
        				
        		    }
                    
                }
            } catch (IOException t) {
            }
        }
    }
    // File Copy Function
 	private static void copyFile(String assetPath, String localPath, Context context) {
 	    try {
 	        InputStream in = context.getAssets().open(assetPath);
 	        FileOutputStream out = new FileOutputStream(localPath);
 	        int read;
 	        byte[] buffer = new byte[4096];
 	        while ((read = in.read(buffer)) > 0) {
 	            out.write(buffer, 0, read);
 	        }
 	        out.close();
 	        in.close();
 	        Log.d(TAG, "File " + assetPath + " copied successfully.");

 	    } catch (IOException e) {
 	        throw new RuntimeException(e);
 	    }
 	}
}