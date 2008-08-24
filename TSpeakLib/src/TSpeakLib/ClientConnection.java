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
import javax.sound.sampled.*;

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
    
    //types of server packet we can recieve:
    public enum serverPacketType {GENERIC_ERROR,UNKNOWN_PACKET,UNHANDLED_PACKET,CONNECTION_REPLY_1,OTHER_KNOWN,
							PING_REPLY,
                                    //and now the audio data types
				    //speex first
                                    VOICE_DATA_SPEEX_3_4,VOICE_DATA_SPEEX_5_2,VOICE_DATA_SPEEX_7_2,VOICE_DATA_SPEEX_9_3,
                                    VOICE_DATA_SPEEX_12_3,VOICE_DATA_SPEEX_16_3,VOICE_DATA_SPEEX_19_5,VOICE_DATA_SPEEX_25_9,
				    //text messages
				    TEXT_MESSAGE,CHAT_MESSAGE};
				    
    //store last error here
    String globalErrorBuffer = new String();
    boolean connected = false;

    //text messages
    class textMessage 
    {
	String msg;
	String senderName;
	boolean isMore = false; //default to false - complicated why! First packet assumed to be start of long message, so this is set to true as soon as we start handling, but first packet will not be the continuation. Or something.
    }
    
    textMessage currentMessage = new textMessage();
    
    //audio crap
    SourceDataLine line;
    public TargetDataLine lineIn; //only public for testing!
    AudioFormat targetFormat;  
    AudioFormat sourceFormat;  
    
    SpeexDecoder speexDecoder;
    SpeexEncoder speexEncoder;
    final int OUTPUT_SAMPLE_RATE = 16000; //speex says this should be 8000 but that runs at 50% speed (no idea why). This works so we keep it.
    
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
	  
	    initialiseAudio();
        }
        catch (Exception e) 
        {
            System.err.println(e);
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

	    speexDecoder = new SpeexDecoder();
	    /*
	     * speexDecoder.init(mode,       //   mode - (0=NB, 1=WB, 2=UWB)
			      sampleRate, //the number of samples per second.
			      channels,   //(1=mono, 2=stereo, ...)
			      enhanced    // perceptual enhancement
			      );
	     * */
	    speexDecoder.init(1, OUTPUT_SAMPLE_RATE,1, true);
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
	    sourceFormat = new AudioFormat(44100.0F, 16, 1,true,false);

	    
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
	    
	    //encoder
	    speexEncoder = new SpeexEncoder();
	    /*
	     * speexEncoder.init(mode,       //   mode - (0=NB, 1=WB, 2=UWB)
	     *		      quality, //encoding quality (0-9?)
			      sampleRate, //the number of samples per second.
			      channels,   //(1=mono, 2=stereo, ...)
			      );
	     * */
	    speexEncoder.init(1,7,OUTPUT_SAMPLE_RATE,1);
	   
	}
	catch (Exception e) 
        {
            System.err.println("Error initialising audio input: " + e);
        }
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
            byte[] header = hexStringToByteArray("f4be0300000000000000000001000000");
            
            //Spacer for a CRC check
            byte[] crc = hexStringToByteArray("00000000");
            
            //This says Teamspeak and then an OS Type (Windows NT atm), both prefixed with string lengths
            byte[] body = hexStringToByteArray("095465616d537065616b00000000000000000000000000000000000000000a57696e646f7773204e54000000000000000000000000000000000000000200000020003c0001");
            
            //needs to be changeable but for now go with unregistered
            byte[] registered = hexStringToByteArray("01");
            
            byte[] login = packByteArray(loginName.getBytes(),29,(byte)0x00);
            byte[] loginLength = {(byte)loginName.length()};
            byte[] password = packByteArray(loginPassword.getBytes(),29,(byte)0x00);
            byte[] passwordLength = {(byte)loginPassword.length()};
            byte[] alias = packByteArray(aliasName.getBytes(),29,(byte)0x00);
            byte[] aliasLength = {(byte)aliasName.length()};
            
            byte[][] messageArray = {header,crc,body,registered,loginLength,login,passwordLength,password,aliasLength,alias};
            
            byte[] message = byteArrayConcat(messageArray);

            crc = hexStringToByteArray(crc32(message));
            
            //now redo WITH the crc
            byte[][] messageArray2 = {header,crc,body,registered,loginLength,login,passwordLength,password,aliasLength,alias};
            message = byteArrayConcat(messageArray2);
            
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
            byte[] headerpart1 = hexStringToByteArray("F0BE0500");
            byte[] headerpart2 = hexStringToByteArray("00000000");
            //more padding too
            byte[] crc = hexStringToByteArray("00000000");
       
            byte[] channelName = packByteArray(channelNameStr.getBytes(),29,(byte)0x00);
            byte[] nameLength = {(byte)channelNameStr.length()};
            byte[] subChannelName = packByteArray(subChannelNameStr.getBytes(),29,(byte)0x00);
            byte[] subChannelLength = {(byte)subChannelNameStr.length()};
            byte[] channelPassword = packByteArray(channelPasswordStr.getBytes(),25,(byte)0x00);
            byte[] passwordLength = {(byte)channelPasswordStr.length()};
            
            
            byte[] bodypart1 = hexStringToByteArray("0100");
                    
            byte[] tail = hexStringToByteArray("00000000");
            
            byte[] cmdCounterBytes = intToByteArray(cmdCounter);
            
            byte[][] messageArray = {headerpart1,serverChunkB,cmdCounterBytes,headerpart2,crc,bodypart1,nameLength,channelName,passwordLength,channelPassword,subChannelLength,subChannelName,serverCrc,tail};
            
            byte[] message = byteArrayConcat(messageArray);

            crc = hexStringToByteArray(crc32(message));
            
            //now redo WITH the crc
            byte[][] messageArray2 = {headerpart1,serverChunkB,cmdCounterBytes,headerpart2,crc,bodypart1,nameLength,channelName,subChannelLength,subChannelName,passwordLength,channelPassword,serverCrc,tail};
            message = byteArrayConcat(messageArray2);
            
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
            byte[] headerpart1 = hexStringToByteArray("f1be0000");
            byte[] acknum = {serverPacket.getData()[0x0c],serverPacket.getData()[0x0d],serverPacket.getData()[0x0e],serverPacket.getData()[0x0f]};
            byte[][] message1 = {headerpart1,serverChunkB,acknum};
            byte[] message = byteArrayConcat(message1);

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
        
        Integer serverheader = byteArrayToInt(headerByte,0);
        
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
				receiveTextMessage(packet);
				packetType = serverPacketType.TEXT_MESSAGE;
			}
			else
			{
//				libtbb_client_decode_textmessage(theTBBClient);
//				libtbb_client_doack(theTBBClient, &acknum);
//				if(theTBBClient->currentmsg.ismore==0)
//					return TBBCLIENT_CHAT_MESSAGE;
				receiveTextMessage(packet);
				packetType = serverPacketType.CHAT_MESSAGE;
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
            byte[] header = hexStringToByteArray("f4be0100");
            byte[] tail = hexStringToByteArray("");
            byte[][] message1 = {header,serverChunkB,intToByteArray(pingCount),tail};
            byte[] message = byteArrayConcat(message1);
            
            byte[] crc = hexStringToByteArray(crc32(message));
            //now redo WITH the crc
            byte[][] message2 = {header,serverChunkB,intToByteArray(pingCount),tail,crc};
            message = byteArrayConcat(message2);
            
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
            pingCount = byteArrayToInt(pingcount,0);
            
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
            byte[] header = hexStringToByteArray("f0beae01");
            byte[] crc = hexStringToByteArray("00000000");
            byte[] pad = hexStringToByteArray("00000000");
            byte[] tail = hexStringToByteArray("0000000002");
            byte[] end = hexStringToByteArray("00");
            
            byte[] idOut = intToByteArray(id);
            byte[] cmdCounterBytes = intToByteArray(cmdCounter);
            
            byte[][] message = {header,serverChunkB,cmdCounterBytes,pad,crc,tail,idOut,msg.getBytes(),end};
            
            cmdCounter++;
            
            byte[] messageOut = byteArrayConcat(message);
            crc = hexStringToByteArray(crc32(messageOut));
            //now redo WITH the crc
            byte[][] message2 = {header,serverChunkB,cmdCounterBytes,pad,crc,tail,idOut,msg.getBytes(),end};
            byte[] message2Out = byteArrayConcat(message2);
            
            DatagramPacket packet = new DatagramPacket(message2Out, message2Out.length,address,port);
            UDPSocket.send(packet);
            
        }
        catch (Exception e) 
        {
           System.err.println(e);
            
           return;
        }
            
    }
    
    private void receiveTextMessage(DatagramPacket packet)
    {
	
	if(DEBUG){System.out.println("Receving text message:");}
	
	if (currentMessage.isMore == true)
	{
	    //this is a followup, so only need to do the simple version
	    receiveBigTextMessage(packet);
	}
	
	//now say there is more to come until we find the 0x00 end-of-message indicator
	currentMessage.isMore = true;
	
	//get the data
	byte[] data = packet.getData();
	
	//see if this is message has more parts to come or find end of message position 
	//(though this should surely be at the end?)
	//ignored for now :D
	
	//populate our pretty message structure
	currentMessage = new textMessage();
	
	currentMessage.msg = "";
	currentMessage.senderName = "";
		
        if(DEBUG){System.out.println(currentMessage.msg);}
    }
    
    private void receiveBigTextMessage(DatagramPacket packet)
    {
	if(DEBUG){System.out.println("Continuation text message packet.");}
    }
    
    public void sendChatMessage(String msg, int id)
    {
        //identical to text message except tail ends in 1 afaik
        try
        {
            byte[] header = hexStringToByteArray("f0beae01");
            byte[] crc = hexStringToByteArray("00000000");
            byte[] pad = hexStringToByteArray("00000000");
            byte[] tail = hexStringToByteArray("0000000001");
            byte[] end = hexStringToByteArray("00");
            
            byte[] idOut = intToByteArray(id);
            byte[] cmdCounterBytes = intToByteArray(cmdCounter);
            
            byte[][] message = {header,serverChunkB,cmdCounterBytes,pad,crc,tail,idOut,msg.getBytes(),end};
            
            cmdCounter++;
            
            byte[] messageOut = byteArrayConcat(message);
            crc = hexStringToByteArray(crc32(messageOut));
            //now redo WITH the crc
            byte[][] message2 = {header,serverChunkB,cmdCounterBytes,pad,crc,tail,idOut,msg.getBytes(),end};
            byte[] message2Out = byteArrayConcat(message2);
            
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
	    line.write(decoded, 0, dataSize);

//	    for (int x = 0; x < dataSize; x++)
//	    {
//		System.out.println((int)decoded[x]);		
//	    }
	   // System.exit(0);
	//    line.write(audioData,0,1000);
            
        }
        catch (Exception e) 
        {
           System.err.println("Error decoding speex audio packet: " + e);   
	   e.printStackTrace();
           return;
        }
    }
    
    /* Since we send 160 bytes (4 speex frames?) It would be a good idea to pass that much wav data ;)
     * Easiest thing is to encode all the data we're given and let the caller split it methinks
     */
    public void encodeSpeexAudioPacket(byte[] audio, byte[] speexData)
    {    
        try
        {    	    
	    int frame_size = 160;
	    
	    
	    
	    byte[] encoded = new byte[frame_size];
	    
	    speexEncoder.processData(audio, 0, audio.length);
//	    
//            for (int i = 0; i < 4; i++)
//            {
//		speexDecoder.processData(false);
//		//speexDecoder.processData(audioData, i*frame_size, frame_size);
//            }
	    int dataSize = speexEncoder.getProcessedDataByteSize();
	    speexData = new byte[dataSize];	
	    speexDecoder.getProcessedData(speexData, 0);

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
	    byte header[] = hexStringToByteArray("f2be000c"); //LibTBB uses 09 as last byte, TS uses 0c
	    byte header2[] = hexStringToByteArray("00000000");
	    byte tail[] = hexStringToByteArray("0000000001");
	    byte[] pBuffer;
	    byte[] crc = hexStringToByteArray("00000000");
	    byte[] midsection = hexStringToByteArray("260005"); //LibTBB uses 001005, TS uses 260005


	    //nb nasty hack using intToByte2Array (special version!) as the voiceSendCount needs to be 2 bytes)
	    byte[][] messageBuffer = {header,serverChunkB, intToByte2Array(voiceSendCount),midsection,data};
	    pBuffer = byteArrayConcat(messageBuffer);

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
    
    /*UTILITY FUNCTIONS*/
    /* These are all private as they will be played with lots knowing me*/ 
    
    private static byte[] hexStringToByteArray(String hex)
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
    
    private static byte[] byteArrayConcat(byte[][] inArray)
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
    
    private static String crc32(byte[] bytes)
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
    
    private static byte[] packByteArray(byte[] originalArray, int requiredSize, byte packing)
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
    
    private static byte[] intToByteArray(int x)
    {
        byte[] buf = new byte[4];
        buf[3]=(byte)((x & 0xff000000)>>>24);
        buf[2]=(byte)((x & 0x00ff0000)>>>16);
        buf[1]=(byte)((x & 0x0000ff00)>>>8);
        buf[0]=(byte)((x & 0x000000ff));
        
        return buf;
    }
    
    //this is lazy of me but I don't want to write a proper one!
    private static byte[] intToByte2Array(int x)
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
    private static int byteArrayToInt(byte[] b, int offset) {
        int value = 0;
        for (int i = 0; i < 4; i++) {
            int shift = (4 - 1 - i) * 8;
            value += (b[i + offset] & 0x000000FF) << shift;
        }
        return value;
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
