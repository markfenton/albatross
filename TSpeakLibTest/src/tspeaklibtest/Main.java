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

public class Main {

    static ClientConnection a_connection;
    
    
    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        // TODO code application logic here
        
        connectionTest();
        
    }
    
    static void connectionTest()
    {
        a_connection = new ClientConnection("ts.deadcodeelimination.com",8767,"","lionftw","Albatross");
        
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
