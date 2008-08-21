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
 
public class KeepAlive extends Thread {

ClientConnection theConnection;

/**	Flag for debugging messages.
    *	If true, some messages are dumped to the console
    *	during operation.	
    */
    private static boolean DEBUG = true;
    
    public KeepAlive(ClientConnection aConnection)
    {
	theConnection = aConnection;
	start();
    }
    
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
	    
	  // Random generator = new Random();
	    
	    byte[] data = new byte[640];
	    
	   // generator.nextBytes(data);
	    
	    theConnection.lineIn.read(data, 0, 640);
	    
	    byte encData[] = new byte[160];
	    
	    theConnection.encodeSpeexAudioPacket(data,encData);
	    
	    theConnection.sendAudioPacket(encData);
	    
	    if(DEBUG)
	    {
		System.out.println("Sending Audio Packet...");
	    }
	    
	    try
	    {
		//this.sleep(100L);
	    }
	     catch (Exception e) 
	    {
	       System.err.println(e);
	    }
	}
    }
    
    
    
}
