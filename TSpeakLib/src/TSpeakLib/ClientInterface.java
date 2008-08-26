/*
 * ClientInterface.java
 * 
 * Copyright (C) 2008 Mark Fenton
 * 
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package TSpeakLib;

/**
 * This file interfaces with (presumably) a GUI - equivilent of callback functions for when messages are received etc.
 * @author markf
 */
public interface ClientInterface
{
    //text message received
    public void textMessageReceived(ClientConnection.textMessage message);
}
