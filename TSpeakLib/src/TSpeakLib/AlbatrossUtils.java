/*
 * AlbatrossUtils.java
 * 
 * Copyright (C) 2008 Mark Fenton
 * 
 * This is based on the code of the TeamBlibbityBlabbity project (http://sourceforge.net/projects/teambb/)
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
 *
 * @author markf
 */
public class AlbatrossUtils {
        /*UTILITY FUNCTIONS*/
    /* Moved from ClientConnection - not private now!*/ 
    
    public static byte[] hexStringToByteArray(String hex)
    {        
        //http://forum.java.sun.com/thread.jspa?threadID=546486
        byte[] bts = new byte[hex.length() / 2];
        
        String subStr;
        int parsedInt = 0;
        
        for (int i = 0; i < bts.length; i++) 
        {
            subStr = hex.substring(2*i, 2*i+2);
            parsedInt = Integer.parseInt(subStr, 16);
            
            bts[i] = (byte) parsedInt;
        }
        
        return bts;
    }
    
    public static byte[] byteArrayConcat(byte[][] inArray)
    {
        byte[] totalArray;
        
        int arraySize = 0;
        
        for (int i = 0; i < inArray.length; i++)
        {
            arraySize += inArray[i].length;
        }
        
        int offset = 0;
        totalArray = new byte[arraySize];
        
        for (int i = 0; i < inArray.length; i++)
        {
            System.arraycopy(inArray[i],0,totalArray,offset,inArray[i].length);
            offset += inArray[i].length;
        }
        
        return totalArray;
    }
    
    public static String crc32(byte[] bytes)
    {
        java.util.zip.CRC32 x = new java.util.zip.CRC32();
        x.update(bytes);
        
        //java CRC is reversed (byte order) for what TS needs. SO....
        String crcString = Long.toHexString(x.getValue());
        
        //pad with leading 0s
        if (crcString.length() % 2 != 0)
        {
            crcString = "0" + crcString;
        }
        
        //reverse in pairs so bytes are in opposite order
        String reverseCrcString = crcString.substring(6,8) + crcString.substring(4,6) + crcString.substring(2,4) + crcString.substring(0,2);
        
        return reverseCrcString;
    }
    
    public static byte[] packByteArray(byte[] originalArray, int requiredSize, byte packing)
    {
        
        byte[] outArray = new byte[requiredSize];
        
        if (originalArray.length > requiredSize)
        {
            for (int i = 0; i < requiredSize; i++)
            {
                outArray[i] = originalArray[i];
            }
        }
        else
        {
            for (int i = 0; i < originalArray.length; i++)
            {
                outArray[i] = originalArray[i];
            }
            
            for (int i = originalArray.length; i < requiredSize; i++)
            {
                outArray[i] = packing;
            }
        }
        
        return outArray;
    }
    
    public static byte[] intToByteArray(int x)
    {
        byte[] buf = new byte[4];
        buf[3]=(byte)((x & 0xff000000)>>>24);
        buf[2]=(byte)((x & 0x00ff0000)>>>16);
        buf[1]=(byte)((x & 0x0000ff00)>>>8);
        buf[0]=(byte)((x & 0x000000ff));
        
        return buf;
    }
    
    //this is lazy of me but I don't want to write a proper one!
    public static byte[] intToByte2Array(int x)
    {
        byte[] buf = new byte[2];
        buf[1]=(byte)((x & 0x0000ff00)>>>8);
        buf[0]=(byte)((x & 0x000000ff));
        
        return buf;
    }
    /**
     * Convert the byte array to an int starting from the given offset.
     *
     * @param b The byte array
     * @param offset The array offset
     * @return The integer
     */
    public static int byteArrayToInt(byte[] b, int offset) {
        int value = 0;
        for (int i = 0; i < 4; i++) {
            int shift = (4 - 1 - i) * 8;
            value += (b[i + offset] & 0x000000FF) << shift;
        }
        return value;
    }

}
