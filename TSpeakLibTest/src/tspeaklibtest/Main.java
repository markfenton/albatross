/*
 * Main.java
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
 * This is just meant to be a test app - connect to the server and launch a thread to send the keep alive pings
 *  It can receive audio 
 * @author markf
 */

import TSpeakLib.*;
import javax.sound.sampled.*;

public class Main {

    static ClientConnection a_connection;
    
    /**	Flag for debugging messages.
    *	If true, some messages are dumped to the console
    *	during operation.	
    */
    private static boolean DEBUG = true;

    //audio crap
    public static SourceDataLine line;
    public static TargetDataLine lineIn; //only public for testing!
    static AudioFormat targetFormat;
    static AudioFormat sourceFormat;
    
    static final int OUTPUT_SAMPLE_RATE = 16000; //speex says this should be 8000 but that runs at 50% speed (no idea why). This works so we keep it.
    
    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        // TODO code application logic here
	
        initialiseAudio();
        
        //run test
        connectionTest();
        
    }
    
    private static void initialiseAudio()
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
    
    static void connectionTest()
    {
        a_connection = new ClientConnection("ts.deadcodeelimination.com",8767,"","lionftw","Albatross");
        
	//register interface
	TestInterface bob = new TestInterface(a_connection);
	
       if(!a_connection.connect())//should really timeout this
       {
	   System.err.println("Failed to connect");
	   System.exit(0);
       }
        
	KeepAlive keepAliveThread = new KeepAlive(a_connection);
        
        while(true) //loop forever and ever :)
        { 
            a_connection.receiveServerPacket();
        }
           
    }

}
