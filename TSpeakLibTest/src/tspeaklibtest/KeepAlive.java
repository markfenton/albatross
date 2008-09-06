/*
 * KeepAlive.java
 * 
 * Copyright (C) 2008 Mark Fenton
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package tspeaklibtest;

/**
 *
 * @author markf
 */

import TSpeakLib.*;
import java.util.*;
import javax.sound.sampled.*;
 
public class KeepAlive extends Thread {

ClientConnection theConnection;

//audio crap
public SourceDataLine line;
public TargetDataLine lineIn; //only public for testing!
AudioFormat targetFormat;
AudioFormat sourceFormat;

final int OUTPUT_SAMPLE_RATE = 16000; //speex says this should be 8000 but that runs at 50% speed (no idea why). This works so we keep it.


/**	Flag for debugging messages.
    *	If true, some messages are dumped to the console
    *	during operation.	
    */
    private static boolean DEBUG = true;
    
    public KeepAlive(ClientConnection aConnection)
    {
	theConnection = aConnection;
        initialiseAudio();
	start();
    }
    
    @Override
    public void run()
    {
	long lastPing = 0;
	
	try
	{
	    this.sleep(1500L);
	}
	 catch (Exception e) 
        {
           System.err.println(e);
        }
	
	while (true)
	{
	    
	    if(System.currentTimeMillis() - lastPing > 500)
	    {
	//	System.out.println("Ping...");
	//	theConnection.pingServer();
		lastPing =System.currentTimeMillis();
	    }
	    
	   
	    
	    byte[] data = new byte[640];
	   //Random generator = new Random();
	   //generator.nextBytes(data);
	    
	    lineIn.read(data, 0, 640);
	    
	    byte encData[] = new byte[160];
	    
	    theConnection.encodeSpeexAudioPacket(data);
	    
            //generator.nextBytes(encData);

            //got rid of this - now sends however much we encode automatically
	    //theConnection.sendAudioPacket(encData);
	    
	    if(DEBUG)
	    {
		System.out.println("Sending Audio Packet...");
                //System.out.println(encData);
	    }
	    
	    try
	    {
		KeepAlive.sleep(100L);
	    }
	     catch (Exception e) 
	    {
	       System.err.println(e);
	    }
	}
    }
    
    private void initialiseAudio()
    {
	try
	{
	    //this is all the output block
	    targetFormat = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, OUTPUT_SAMPLE_RATE, 16, 1,2, OUTPUT_SAMPLE_RATE,false);

	    SourceDataLine.Info info = new DataLine.Info(SourceDataLine.class,targetFormat); // format is an AudioFormat object
	    if (!AudioSystem.isLineSupported(info)) {
		System.err.println("Audio output type not supported.");
	    }

	    line = (SourceDataLine) AudioSystem.getLine(info);
	    line.open(targetFormat);
	    line.start();
	}
	catch (Exception e) 
        {
            System.err.println("Error initialising audio output: " + e);
        }
	
	try
	{
	    //Audio input time!
	    //list all system mixers
	    if(DEBUG){System.out.println("System Mixers Available: ");}
	    Mixer.Info[]	aInfos = AudioSystem.getMixerInfo();
	    for (int i = 0; i < aInfos.length; i++)
	    {
		if(DEBUG)
		{
		    
		    System.out.println(aInfos[i].getName() + aInfos[i].getDescription());
		}
		
	    }
	    
	    //now get all the mixers
	    Mixer[] aMixer = new Mixer[aInfos.length];
	    for (int i = 0; i < aInfos.length; i++)
	    {
		    aMixer[i] = AudioSystem.getMixer(aInfos[i]);
	    }

	    
	    
	    //this is all the input block
	    sourceFormat = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, OUTPUT_SAMPLE_RATE, 16, 1,2, OUTPUT_SAMPLE_RATE,false);

	    
	    int mixerIndex = 2;
	    Mixer.Info ourMixerInfo = aInfos[mixerIndex]; //this is the mixer we will actually use. Is hardcoded for my test system at present.
	    Mixer ourMixer = aMixer[mixerIndex];
	    
	    //list all mixer lines
	    if(DEBUG){System.out.println("Mixer Lines Available: ");}
	    Line.Info[] lineInfos = ourMixer.getTargetLineInfo();
	    for (int i = 0; i < lineInfos.length; i++)
	    {
		if(DEBUG)
		{
		    System.out.println(lineInfos[i]);
		}
	    }
	    
	    //now need a line from this
	    //Line.Info lineInfo = lineInfos[0]; //this is the mixer line we will use for input. more nasty hardcoding so we use the first one.
	    DataLine.Info lineInfo = new DataLine.Info(TargetDataLine.class,sourceFormat); //this is a test bodge as we don't know what we will get...
	    
	    
	    //now test and get the line.
	    if (!ourMixer.isLineSupported(lineInfo)) {
		System.err.println("Audio input type not supported.");
	    }

	    

	    
	    lineIn = (TargetDataLine) ourMixer.getLine(lineInfo);
	   
	    if(DEBUG){System.out.println("Input line used:" + lineIn);}

	    lineIn.open(sourceFormat);
	    lineIn.start();
	    
	    
	}
	catch (Exception e) 
        {
            System.err.println("Error initialising audio input: " + e);
        }
    }
    
}
