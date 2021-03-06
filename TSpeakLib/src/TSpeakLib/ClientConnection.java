/*
 * ClientConnection.java
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

import java.io.*;
import java.net.*;
import java.math.*;
import org.xiph.speex.*;
import java.util.*;

public class ClientConnection {
    
    /**	Flag for debugging messages.
    *	If true, some messages are dumped to the console
    *	during operation.	
    */
    private static boolean DEBUG = true;
    
    DatagramSocket UDPSocket;
    InetAddress address;
    Integer port;
    
    //userdata
    String globalPassword = "";
    String globalUsername = "";
    String globalAlias = "";

    String channelName = "Arena 1";
    
    //nasty bits for the protocol
    byte[] serverChunkB = {(byte)0x00};
    Integer cmdCounter = 1;
    byte[] serverCrc = new byte[4];
    
    Long lastServerData = -1L; //last time server sent us data
    Long lastPing = -1L; //last we pinged the server
    int pingCount = 0;
    int voiceSendCount = 0;
    
    //Audio buffers
    public List inputAudioBuffer = Collections.synchronizedList(new ArrayList()); //lovely pcm audio from the client
    public List outputAudioBuffer = Collections.synchronizedList(new ArrayList()); //lovely pcm audio going out to the client

    //speex
    SpeexDecoder speexDecoder;
    SpeexEncoder speexEncoder;
    
    //types of server packet we can recieve:
    public enum serverPacketType {GENERIC_ERROR,UNKNOWN_PACKET,UNHANDLED_PACKET,CONNECTION_REPLY_1,OTHER_KNOWN,
							PING_REPLY,
                                    //and now the audio data types
				    //speex first
                                    VOICE_DATA_SPEEX_3_4,VOICE_DATA_SPEEX_5_2,VOICE_DATA_SPEEX_7_2,VOICE_DATA_SPEEX_9_3,
                                    VOICE_DATA_SPEEX_12_3,VOICE_DATA_SPEEX_16_3,VOICE_DATA_SPEEX_19_5,VOICE_DATA_SPEEX_25_9,
				    //text messages
				    INCOMPLETE_MESSAGE,TEXT_MESSAGE,CHAT_MESSAGE};
				    
    //store last error here
    String globalErrorBuffer = new String();
    boolean connected = false;

    //text messages
    public class textMessage 
    {
	public String msg = "";
	public String senderName = "";
	public boolean isMore = false; //default to false - complicated why! First packet assumed to be start of long message, so this is set to true as soon as we start handling, but first packet will not be the continuation. Or something.
    }
    
    textMessage currentMessage = new textMessage();
    
    //callback interface
    private ClientInterface clientInterface;
    
    public ClientConnection(String serverAddress, int port, String username, String password, String alias)
    {
        try 
        {
            this.UDPSocket = new DatagramSocket();
            this.address = InetAddress.getByName(serverAddress);
            this.port = port;   

	    //for registered, pass username and password. for password only, just the password. Alias is optional (depends on server settings)
	    
	    this.globalUsername = username;
	    this.globalPassword = password;
	    this.globalAlias = alias;
	  
	    initialiseAudioEncoding();
        }
        catch (Exception e) 
        {
            System.err.println(e);
        }
    }
    
    private void initialiseAudioEncoding()
    {
        final int OUTPUT_SAMPLE_RATE = 16000; //speex says this should be 8000 but that runs at 50% speed (no idea why). This works so we keep it.
        
        //speex decoder
        speexDecoder = new SpeexDecoder();
        /*
         * speexDecoder.init(mode,       //   mode - (0=NB, 1=WB, 2=UWB)
                          sampleRate, //the number of samples per second.
                          channels,   //(1=mono, 2=stereo, ...)
                          enhanced    // perceptual enhancement
                          );
         * */
        speexDecoder.init(1, OUTPUT_SAMPLE_RATE,1, true);

        //speex encoder
        speexEncoder = new SpeexEncoder();
        /*
         * speexEncoder.init(mode,       //   mode - (0=NB, 1=WB, 2=UWB)
         *		      quality, //encoding quality (0-9?)
                          sampleRate, //the number of samples per second.
                          channels,   //(1=mono, 2=stereo, ...)
                          );
         * */
        speexEncoder.init(1,7,44100,1);
        
    }
    
    
    public boolean connect()
    {
	 sendConnectionPacket1(globalUsername,globalPassword,globalAlias);
	 receiveServerPacket(); //receive reply and send packet 2
	 receiveServerPacket(); //recevie reply to 2. should now be connected
	 return connected;
    }
    
    private void sendConnectionPacket1(String loginName,String loginPassword,String aliasName)
    {
        try
        {   
            //This is a generic header thing
            byte[] header = AlbatrossUtils.hexStringToByteArray("f4be0300000000000000000001000000");
            
            //Spacer for a CRC check
            byte[] crc = AlbatrossUtils.hexStringToByteArray("00000000");
            
            //This says Teamspeak and then an OS Type (Windows NT atm), both prefixed with string lengths
            byte[] body = AlbatrossUtils.hexStringToByteArray("095465616d537065616b00000000000000000000000000000000000000000a57696e646f7773204e54000000000000000000000000000000000000000200000020003c0001");
            
            //needs to be changeable but for now go with unregistered
            byte[] registered = AlbatrossUtils.hexStringToByteArray("01");
            
            byte[] login = AlbatrossUtils.packByteArray(loginName.getBytes(),29,(byte)0x00);
            byte[] loginLength = {(byte)loginName.length()};
            byte[] password = AlbatrossUtils.packByteArray(loginPassword.getBytes(),29,(byte)0x00);
            byte[] passwordLength = {(byte)loginPassword.length()};
            byte[] alias = AlbatrossUtils.packByteArray(aliasName.getBytes(),29,(byte)0x00);
            byte[] aliasLength = {(byte)aliasName.length()};
            
            byte[][] messageArray = {header,crc,body,registered,loginLength,login,passwordLength,password,aliasLength,alias};
            
            byte[] message = AlbatrossUtils.byteArrayConcat(messageArray);

            crc = AlbatrossUtils.hexStringToByteArray(AlbatrossUtils.crc32(message));
            
            //now redo WITH the crc
            byte[][] messageArray2 = {header,crc,body,registered,loginLength,login,passwordLength,password,aliasLength,alias};
            message = AlbatrossUtils.byteArrayConcat(messageArray2);
            
            DatagramPacket packet = new DatagramPacket(message, message.length,address, port);

            UDPSocket.send(packet);
        } 
        catch (Exception e) 
        {
            System.err.println(e);
        }
    }
    
    private void sendConnectionPacket2( String channelNameStr,String subChannelNameStr,String channelPasswordStr)
    {
        try
        {
            //this seems to increment, but other that that more generic header fun
            byte[] headerpart1 = AlbatrossUtils.hexStringToByteArray("F0BE0500");
            byte[] headerpart2 = AlbatrossUtils.hexStringToByteArray("00000000");
            //more padding too
            byte[] crc = AlbatrossUtils.hexStringToByteArray("00000000");
       
            byte[] channelName = AlbatrossUtils.packByteArray(channelNameStr.getBytes(),29,(byte)0x00);
            byte[] nameLength = {(byte)channelNameStr.length()};
            byte[] subChannelName = AlbatrossUtils.packByteArray(subChannelNameStr.getBytes(),29,(byte)0x00);
            byte[] subChannelLength = {(byte)subChannelNameStr.length()};
            byte[] channelPassword = AlbatrossUtils.packByteArray(channelPasswordStr.getBytes(),25,(byte)0x00);
            byte[] passwordLength = {(byte)channelPasswordStr.length()};
            
            
            byte[] bodypart1 = AlbatrossUtils.hexStringToByteArray("0100");
                    
            byte[] tail = AlbatrossUtils.hexStringToByteArray("00000000");
            
            byte[] cmdCounterBytes = AlbatrossUtils.intToByteArray(cmdCounter);
            
            byte[][] messageArray = {headerpart1,serverChunkB,cmdCounterBytes,headerpart2,crc,bodypart1,nameLength,channelName,passwordLength,channelPassword,subChannelLength,subChannelName,serverCrc,tail};
            
            byte[] message = AlbatrossUtils.byteArrayConcat(messageArray);

            crc = AlbatrossUtils.hexStringToByteArray(AlbatrossUtils.crc32(message));
            
            //now redo WITH the crc
            byte[][] messageArray2 = {headerpart1,serverChunkB,cmdCounterBytes,headerpart2,crc,bodypart1,nameLength,channelName,subChannelLength,subChannelName,passwordLength,channelPassword,serverCrc,tail};
            message = AlbatrossUtils.byteArrayConcat(messageArray2);
            
            DatagramPacket packet = new DatagramPacket(message, message.length,address, port);
            
            //increment cmdCounter
            cmdCounter++;
            
            UDPSocket.send(packet);
        } 
        catch (Exception e) 
        {
            System.err.println(e);
        }
    }
    
    private boolean receiveConnectionPacket1Reply(DatagramPacket packet)
    {
        try
        {   
            byte[] data = packet.getData();
            
            //get rid of this string
            
            
            //packet should be 436 bytes...
            if (packet.getLength() != 436)
            {
                //panic!
                globalErrorBuffer = "Reply Packet 1 length incorrect. Should be 436 bytes, recieved " + Long.toString(packet.getLength());
                System.err.println(globalErrorBuffer);
                return false;
            }
            
            //now check for login success - failure = 0xffffff from 0x59 -> 0x5b
            if (data[0x59] == 0xff && data[0x5a] == 0xff  && data[0x5b] == 0xff )
            {
                globalErrorBuffer = "Login Failure. Incorrect username or password.";
                System.err.println(globalErrorBuffer);
                return false;                
            }
            
            //cba checking server crc is correct cos we are lazy, but we shoulddo here
            
            //now store bits and bobs
            byte[] thecrc = {data[0x10],data[0x11],data[0x12], data[0x13]};
            serverCrc = thecrc;
            
            byte[] chunkB = {data[0xac],data[0xad],data[0xae], data[0xaf],data[0xb0],data[0xb1],data[0xb2], data[0xb3]};
            serverChunkB = chunkB;
            
            //update last seen time
            lastServerData = System.currentTimeMillis();
            
            //System.err.println(data.toString());
            
            return true;
        } 
        catch (Exception e) 
        {
           System.err.println(e);
            
           return false;
        }
    }
    
    private void acknowledgeServerPacket(DatagramPacket serverPacket)
    {
        try
        {
            byte[] headerpart1 = AlbatrossUtils.hexStringToByteArray("f1be0000");
            byte[] acknum = {serverPacket.getData()[0x0c],serverPacket.getData()[0x0d],serverPacket.getData()[0x0e],serverPacket.getData()[0x0f]};
            byte[][] message1 = {headerpart1,serverChunkB,acknum};
            byte[] message = AlbatrossUtils.byteArrayConcat(message1);

            DatagramPacket packet = new DatagramPacket(message, message.length,address,port);
            UDPSocket.send(packet);
        }
        catch (Exception e) 
        {
           System.err.println(e);
            
           return;
        }
        
        return;
    }
    
    public void receiveServerPacket()
    {
        try
        {
            byte[] message = new byte[1024]; //1024 is arbitary. LibTBB uses 500, python bot 600.
            DatagramPacket packet = new DatagramPacket(message, message.length);
            UDPSocket.receive(packet);
            
            serverPacketType packetType = handleServerPacket(packet);
            
            switch(packetType)
            {
                case CONNECTION_REPLY_1:
                    //this shouuld have gone ok if we get this so send next packet
                    sendConnectionPacket2(channelName,"","");
                    //return here as we don't want to do a normal ack
                    return;
                    //break; //not needed cos of return
                case GENERIC_ERROR:
                    //something else went wrong - display the globalErrorBuffer
                    System.err.println(globalErrorBuffer);
                    break;
                case OTHER_KNOWN:
                    //looks like we don't need to do anything else except acknowledge
                    break;
		case PING_REPLY:
		    //don't want to acknowledge these
		    break;
                case UNHANDLED_PACKET:
                    //this means we don't handle the type yet
                    if(DEBUG){System.out.println("Known but unhandled packet from server! Type header was: " + globalErrorBuffer);}
                    break;
                //AUDIO TYPES!
                case VOICE_DATA_SPEEX_3_4:
		case VOICE_DATA_SPEEX_5_2:
		case VOICE_DATA_SPEEX_7_2:
		case VOICE_DATA_SPEEX_9_3:
		case VOICE_DATA_SPEEX_12_3:
		case VOICE_DATA_SPEEX_16_3:
		case  VOICE_DATA_SPEEX_19_5:
		case VOICE_DATA_SPEEX_25_9:
                    handleSpeexPacket(message,packetType);
                    break;
		case TEXT_MESSAGE:
		case CHAT_MESSAGE:
		    //need to notify the app that there is a new message
		    if(DEBUG){System.out.println(currentMessage.senderName + ": " + currentMessage.msg);}
		    clientInterface.textMessageReceived(currentMessage);
		    break;
		case INCOMPLETE_MESSAGE:
		    //don't want return incomplete messages
		    break;
                case UNKNOWN_PACKET: 
                default:
                    //this means we don't know the type - uhoh.
                    if(DEBUG){System.out.println("Unknown packet type from server! Type header was: " + globalErrorBuffer);}
                    break;
            }
            
            //Acknowledge the packet...
            acknowledgeServerPacket(packet);
            
            //Update last seen time
            lastServerData = System.currentTimeMillis();
        }
        catch (Exception e) 
        {
           System.err.println(e);
            
           return;
        }
        return;
    }
    
    private serverPacketType handleServerPacket(DatagramPacket packet)
    {
        serverPacketType packetType;
        byte[] message = packet.getData();
        
        byte[] headerByte = {message[3],message[2],message[1],message[0]};
        
        Integer serverheader = AlbatrossUtils.byteArrayToInt(headerByte,0);
        
        packetType = serverPacketType.UNHANDLED_PACKET; //for all the ones we haven't handled yet

	switch(serverheader)
	{
		case 0x0000bef1: //seems to be the reply to connection packet 2... (not sure about this)
		    packetType = serverPacketType.OTHER_KNOWN;
		    connected = true;
		    break;
		    
		case 0x0004bef4:	//connection packet 1 reply
			if (receiveConnectionPacket1Reply(packet))
                        {
                            packetType = serverPacketType.CONNECTION_REPLY_1;
                        }
                        else 
                        {
                            packetType = serverPacketType.GENERIC_ERROR;
                        }                    
			break;
		
		case 0x0082bef0:	// text message
			if(message[0x1c]==0x02) //text/chat flag
			{
//				libtbb_client_decode_textmessage(theTBBClient);
//				libtbb_client_doack(theTBBClient, &acknum);
//				if(theTBBClient->currentmsg.ismore==0)
//					return TBBCLIENT_TEXT_MESSAGE;
				packetType = receiveTextMessage(packet);
			}
			else
			{
//				libtbb_client_decode_textmessage(theTBBClient);
//				libtbb_client_doack(theTBBClient, &acknum);
//				if(theTBBClient->currentmsg.ismore==0)
//					return TBBCLIENT_CHAT_MESSAGE;
				packetType = receiveTextMessage(packet); //we treat chat messages as text messages for now
			}
			break;
		case 0x0007bef0:
//			if(!libtbb_client_check_server_crc32(theTBBClient, 0x14))
//				return TBBCLIENT_BAD_CRC32;
//			//printf("player list from server\n");
//			libtbb_client_delete_playerlist(theTBBClient);
//			libtbb_client_decode_playerlist(theTBBClient);
//			libtbb_client_doack(theTBBClient, &acknum);
//			return TBBCLIENT_NEW_PLAYERLIST;
			break;
		case 0x006cbef0:
//			if(!libtbb_client_check_server_crc32(theTBBClient, 0x14))
//				return TBBCLIENT_BAD_CRC32;
//			//printf("channel player list from server\n");
//			//tbb_client_delete_playerlist(theTBBClient);
//			libtbb_client_decode_channel_playerlist(theTBBClient);
////			tbb_client_decode_playerlist(theTBBClient);
//			libtbb_client_doack(theTBBClient, &acknum);
//			return TBBCLIENT_NEW_PLAYERLIST;
			break;
			
		case 0x0067bef0:
//			if(!libtbb_client_check_server_crc32(theTBBClient, 0x14))
//				return TBBCLIENT_BAD_CRC32;
//			//printf("channel change from server\n");
//			libtbb_client_decode_channelchange(theTBBClient);
//			//tbb_client_delete_playerlist(theTBBClient);
////			tbb_client_decode_channel_playerlist(theTBBClient);
//	//		tbb_client_decode_playerlist(theTBBClient);
//			libtbb_client_doack(theTBBClient, &acknum);
//			return TBBCLIENT_NEW_PLAYERLIST;
			break;
		case 0x0006bef0:
////			printf("channel list from server\n");
//			if(!libtbb_client_check_server_crc32(theTBBClient, 0x14))
//				return TBBCLIENT_BAD_CRC32;
//			return libtbb_client_decode_channellist(theTBBClient);
			break;
		case 0x06ebef0:
//			//printf("channel list update from server\n");
//			if(!libtbb_client_check_server_crc32(theTBBClient, 0x14))
//				return TBBCLIENT_BAD_CRC32;
//				libtbb_client_decode_channellist(theTBBClient);
//				
//				return TBBCLIENT_NEW_CHANNELLIST;
			break;
		case 0x0066bef0:
//			//printf("player kick from server\n");
//			libtbb_client_doack(theTBBClient, &acknum);
//			if(theTBBClient->servertype==TBBCLIENT_SERVER_TYPE_PUBLIC)
//			{
//				return libtbb_client_player_kicked(theTBBClient);
////				return TBBCLIENT_PLAYERLEFT;
//			}
			break;
		case 0x0065bef0:
//			//printf("player left from server\n");
//			libtbb_client_doack(theTBBClient, &acknum);
//			return libtbb_client_player_kicked(theTBBClient);
//			//return TBBCLIENT_PLAYERLEFT;
			break;
		case 0x0064bef0: // player join
//			//printf("player join from server\n");
//			libtbb_client_doack(theTBBClient, &acknum);
//			libtbb_client_player_join(theTBBClient);
//			return TBBCLIENT_PLAYERJOINED;
			break;
		case 0x0068bef0: // player join
//			//printf("player join from server\n");
//			libtbb_client_doack(theTBBClient, &acknum);
//			libtbb_client_player_statuschange(theTBBClient);
//			return TBBCLIENT_PLAYERSTATUSCHANGE;
			break;
		case 0x0073bef0: // channel deleted
//			//printf("channel deleted from server\n");
//			libtbb_client_doack(theTBBClient, &acknum);
//			libtbb_client_decode_channeldelete(theTBBClient);
			break;
		case 0x006fbef0: // channel name change
//			//printf("channel name change\n");
//			libtbb_client_doack(theTBBClient, &acknum);
//			libtbb_client_decode_channel_name_change(theTBBClient);
			break;
		case 0x0070bef0: // channel topic change
//			//printf("channel topic change\n");
//			libtbb_client_doack(theTBBClient, &acknum);
//			libtbb_client_decode_channel_topic_change(theTBBClient);
			break;
		case 0x0071bef0: // channel pwed change
//			//printf("channel pwed change\n");
//			libtbb_client_doack(theTBBClient, &acknum);
//			libtbb_client_decode_channel_pwed_change(theTBBClient);
			break;
		case 0xfc0fbef0: // chat msg bouce
//			//printf("chat msg bouce\n");
//			libtbb_client_doack(theTBBClient, &acknum);
//			return TBBCLIENT_CHAT_MSG_BOUNCE;
			break;
			
		case 0x0500bef3: // voice data VOICE_DATA_SPEEX_3_4
			packetType = serverPacketType.VOICE_DATA_SPEEX_3_4;
			break;
		case 0x0600bef3: // voice data VOICE_DATA_SPEEX_5_2
			packetType = serverPacketType.VOICE_DATA_SPEEX_5_2;
			break;
		case 0x0700bef3: // voice data VOICE_DATA_SPEEX_7_2
			packetType = serverPacketType.VOICE_DATA_SPEEX_7_2;
			break;
		case 0x0800bef3: // voice data VOICE_DATA_SPEEX_9_3
			packetType = serverPacketType.VOICE_DATA_SPEEX_9_3;
			break;
		case 0x0900bef3: // voice data VOICE_DATA_SPEEX_12_3
			packetType = serverPacketType.VOICE_DATA_SPEEX_12_3;
			break;
		case 0x0a00bef3: // voice data VOICE_DATA_SPEEX_16_3
			packetType = serverPacketType.VOICE_DATA_SPEEX_16_3;
			break;
		case 0x0b00bef3: // voice data VOICE_DATA_SPEEX_19_5
			packetType = serverPacketType.VOICE_DATA_SPEEX_19_5;
			break;
		case 0x0c00bef3: // voice data VOICE_DATA_SPEEX_25_9
			packetType = serverPacketType.VOICE_DATA_SPEEX_25_9;
			break;
			
		case 0x0002bef4: //ping response
//			if(!libtbb_client_check_server_crc32(theTBBClient, 0x10))
//				return TBBCLIENT_BAD_CRC32;
                        receivePing(packet);
			packetType = serverPacketType.PING_REPLY;
			break;
		default:
//			libtbb_client_doack(theTBBClient, &acknum);
                        packetType = serverPacketType.UNKNOWN_PACKET;
			break;
	}
        
        
        globalErrorBuffer = Integer.toHexString(serverheader);
        
        return packetType; //no idea what this is!
    }
    
    public void pingServer()
    {
       try
        {
            byte[] header = AlbatrossUtils.hexStringToByteArray("f4be0100");
            byte[] tail = AlbatrossUtils.hexStringToByteArray("");
            byte[][] message1 = {header,serverChunkB,AlbatrossUtils.intToByteArray(pingCount),tail};
            byte[] message = AlbatrossUtils.byteArrayConcat(message1);
            
            byte[] crc = AlbatrossUtils.hexStringToByteArray(AlbatrossUtils.crc32(message));
            //now redo WITH the crc
            byte[][] message2 = {header,serverChunkB,AlbatrossUtils.intToByteArray(pingCount),tail,crc};
            message = AlbatrossUtils.byteArrayConcat(message2);
            
            DatagramPacket packet = new DatagramPacket(message, message.length,address,port);
            UDPSocket.send(packet);
        }
        catch (Exception e) 
        {
           System.err.println(e);
            
           return;
        }
        
        return; 
    }
    
    private boolean receivePing(DatagramPacket packet)
    {
        try
        {   
            byte[] data = packet.getData();
                        
            //cba checking server crc is correct cos we are lazy, but we shoulddo here
            
            //now store bits and bobs
            byte[] pingcount = {data[0xc],data[0xd],data[0xe], data[0xf]};
            pingCount = AlbatrossUtils.byteArrayToInt(pingcount,0);
            
            //update last seen time
            lastServerData = System.currentTimeMillis();
            lastPing = lastServerData;            
            return true;
        } 
        catch (Exception e) 
        {
           System.err.println(e);
            
           return false;
        }
    }
    
    public void sendTextMessage(String msg, int id)
    {
        try
        {
            byte[] header = AlbatrossUtils.hexStringToByteArray("f0beae01");
            byte[] crc = AlbatrossUtils.hexStringToByteArray("00000000");
            byte[] pad = AlbatrossUtils.hexStringToByteArray("00000000");
            byte[] tail = AlbatrossUtils.hexStringToByteArray("0000000002");
            byte[] end = AlbatrossUtils.hexStringToByteArray("00");
            
            byte[] idOut = AlbatrossUtils.intToByteArray(id);
            byte[] cmdCounterBytes = AlbatrossUtils.intToByteArray(cmdCounter);
            
            byte[][] message = {header,serverChunkB,cmdCounterBytes,pad,crc,tail,idOut,msg.getBytes(),end};
            
            cmdCounter++;
            
            byte[] messageOut = AlbatrossUtils.byteArrayConcat(message);
            crc = AlbatrossUtils.hexStringToByteArray(AlbatrossUtils.crc32(messageOut));
            //now redo WITH the crc
            byte[][] message2 = {header,serverChunkB,cmdCounterBytes,pad,crc,tail,idOut,msg.getBytes(),end};
            byte[] message2Out = AlbatrossUtils.byteArrayConcat(message2);
            
            DatagramPacket packet = new DatagramPacket(message2Out, message2Out.length,address,port);
            UDPSocket.send(packet);
            
        }
        catch (Exception e) 
        {
           System.err.println(e);
            
           return;
        }
            
    }
    
    private serverPacketType receiveTextMessage(DatagramPacket packet)
    {	
	if (currentMessage.isMore == true)
	{
	    //this is a followup, so only need to do the simple version
	    return receiveBigTextMessage(packet);
	}
	else
	{
	    //this is the first packet so make a new message
	    currentMessage = new textMessage();
	}
	
	//now say there is more to come until we find the 0x00 end-of-message indicator
	currentMessage.isMore = true;
	
	//get the data
	byte[] data = packet.getData();
	
	//see if this is message has more parts to come or find end of message position 
	//(though this should surely be at the end?)
	//ignored for now :D
	
	//populate our pretty message structure
	//the sender's name
	for (int i = 30; i < (data[29] + 30); i++) //step throug hthe length of the name above the offset
	{
	    currentMessage.senderName = currentMessage.senderName + (char)data[i];
	}
	
	
	//the actual message
	for (int i = 0x3b; i < data.length; i++)
	{
	    if (data[i] == 0x00) //null char - end of message
	    {
		currentMessage.isMore = false; //this is the only packet
		break; //don't do any more
	    }
	    currentMessage.msg = currentMessage.msg + (char)data[i];
	}	
	
	if (currentMessage.isMore)
	{
	    return serverPacketType.INCOMPLETE_MESSAGE;
	}
	else
	{
	    return serverPacketType.TEXT_MESSAGE;
	}
    }
    
    private serverPacketType receiveBigTextMessage(DatagramPacket packet)
    {
	//now say there is more to come until we find the 0x00 end-of-message indicator
	currentMessage.isMore = true;
	
	//get the data
	byte[] data = packet.getData();
	
	//the actual message
	for (int i = 0x18; i < data.length; i++)
	{
	    if (data[i] == 0x00) //null char - end of message
	    {
		currentMessage.isMore = false; //this is the only packet
		break; //don't do any more
	    }
	    currentMessage.msg = currentMessage.msg + (char)data[i];
	}

	if (currentMessage.isMore)
	{
	    return serverPacketType.INCOMPLETE_MESSAGE;
	}
	else
	{
	    return serverPacketType.TEXT_MESSAGE;
	}
    }
    
    public void sendChatMessage(String msg, int id)
    {
        //identical to text message except tail ends in 1 afaik
        try
        {
            byte[] header = AlbatrossUtils.hexStringToByteArray("f0beae01");
            byte[] crc = AlbatrossUtils.hexStringToByteArray("00000000");
            byte[] pad = AlbatrossUtils.hexStringToByteArray("00000000");
            byte[] tail = AlbatrossUtils.hexStringToByteArray("0000000001");
            byte[] end = AlbatrossUtils.hexStringToByteArray("00");
            
            byte[] idOut = AlbatrossUtils.intToByteArray(id);
            byte[] cmdCounterBytes = AlbatrossUtils.intToByteArray(cmdCounter);
            
            byte[][] message = {header,serverChunkB,cmdCounterBytes,pad,crc,tail,idOut,msg.getBytes(),end};
            
            cmdCounter++;
            
            byte[] messageOut = AlbatrossUtils.byteArrayConcat(message);
            crc = AlbatrossUtils.hexStringToByteArray(AlbatrossUtils.crc32(messageOut));
            //now redo WITH the crc
            byte[][] message2 = {header,serverChunkB,cmdCounterBytes,pad,crc,tail,idOut,msg.getBytes(),end};
            byte[] message2Out = AlbatrossUtils.byteArrayConcat(message2);
            
            DatagramPacket packet = new DatagramPacket(message2Out, message2Out.length,address,port);
            UDPSocket.send(packet);
            
        }
        catch (Exception e) 
        {
           System.err.println(e);
            
           return;
        }
            
    }
    
    private void handleSpeexPacket(byte[] message, serverPacketType packetType)
    {  
	decodeSpeexAudioPacket(message);
	System.out.println("sound!");
	return;
    }
    
    private void decodeSpeexAudioPacket(byte[] message)
    {
        //copy audio portion of data into new array
        int audioDataSize = message.length - 0x17 - 1;
        byte[] audioData = new byte[audioDataSize];
        System.arraycopy(message,0x17,audioData,0,audioDataSize);
        
        try
        {    	    
	    int frame_size = 160;
	    
	    
	    
	    byte[] decoded = null;
	    
	    speexDecoder.processData(audioData, 0, frame_size);
	    
            for (int i = 0; i < 4; i++)
            {
		speexDecoder.processData(false);
		//speexDecoder.processData(audioData, i*frame_size, frame_size);
            }
	    int dataSize = speexDecoder.getProcessedDataByteSize();
	    decoded = new byte[dataSize];	
	    speexDecoder.getProcessedData(decoded, 0);
	    
            //write data into the output buffer and notify the client app
            outputAudioBuffer.add(decoded);
            
            clientInterface.audioDataReceived();
            
        }
        catch (Exception e) 
        {
           System.err.println("Error decoding speex audio packet: " + e);   
	   e.printStackTrace();
           return;
        }
    }
    
    /* Encodes pcm data to speex and sends it to the server.
     */
    public void encodeSpeexAudioPacket(byte[] audio)
    {    
        try
        {    	    
	    int frame_size = 160;
	    
	    
	    byte[] speexData;
	    
	    speexEncoder.processData(audio, 0, audio.length);
//	    
//            for (int i = 0; i < 4; i++)
//            {
//                speexEncoder.processData(false);
//		//speexDecoder.processData(audioData, i*frame_size, frame_size);
//            }
	    int dataSize = speexEncoder.getProcessedDataByteSize();
	    speexData = new byte[dataSize];	
	    speexEncoder.getProcessedData(speexData, 0);
            
            // now send the data

            if(DEBUG){System.out.println("Encoded audio size: " + dataSize);}

	    return;            
        }
        catch (Exception e) 
        {
           System.err.println("Error encoding Speex audio data: " + e);   
	   e.printStackTrace();
           return;
        }
    }
    
    //obviously data is the audio, and it needs to be encoded (correctly for the current channel)
    public void sendAudioPacket(byte[] data)
    {
	try
	{
	    byte header[] = AlbatrossUtils.hexStringToByteArray("f2be000c"); //LibTBB uses 09 as last byte, TS uses 0c
	    byte header2[] = AlbatrossUtils.hexStringToByteArray("00000000");
	    byte tail[] = AlbatrossUtils.hexStringToByteArray("0000000001");
	    byte[] pBuffer;
	    byte[] crc = AlbatrossUtils.hexStringToByteArray("00000000");
	    byte[] midsection = AlbatrossUtils.hexStringToByteArray("260005"); //LibTBB uses 001005, TS uses 260005


	    //nb nasty hack using intToByte2Array (special version!) as the voiceSendCount needs to be 2 bytes)
	    byte[][] messageBuffer = {header,serverChunkB, AlbatrossUtils.intToByte2Array(voiceSendCount),midsection,data};
	    pBuffer = AlbatrossUtils.byteArrayConcat(messageBuffer);

	    /* Oddly the TBB code doesn't use tail, head2 or crc after defining them all */

	     DatagramPacket packet = new DatagramPacket(pBuffer, pBuffer.length,address,port);
	     UDPSocket.send(packet);

	     voiceSendCount++;
	}
	catch(Exception e)
	{
	    System.err.println("Error sending voice: " + e);
	}
    }
        
    public void disconnect()
    {
        UDPSocket.disconnect();
    }

    public void registerInterface(ClientInterface client)
    {
	clientInterface = client;
    }


    /* GET/SETS */
    
    public Long  getLastServerData()
    {
        return lastServerData;
    }
    public Long  getLastPing()
    {
        return lastPing;
    }
    public boolean isConnected()
    {
        return connected;
    }
}
