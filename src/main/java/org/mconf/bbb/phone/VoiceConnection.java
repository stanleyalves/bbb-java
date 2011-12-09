/*
 * GT-Mconf: Multiconference system for interoperable web and mobile
 * http://www.inf.ufrgs.br/prav/gtmconf
 * PRAV Labs - UFRGS
 * 
 * This file is part of Mconf-Mobile.
 *
 * Mconf-Mobile is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Mconf-Mobile is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Mconf-Mobile.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.mconf.bbb.phone;

import java.util.Map;
import java.util.concurrent.Executor;

import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.mconf.bbb.BigBlueButtonClient;
import org.mconf.bbb.RtmpConnection;
import org.mconf.bbb.api.JoinedMeeting;
import org.red5.server.so.SharedObjectMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.flazr.amf.Amf0Object;
import com.flazr.rtmp.RtmpDecoder;
import com.flazr.rtmp.RtmpEncoder;
import com.flazr.rtmp.RtmpMessage;
import com.flazr.rtmp.client.ClientHandshakeHandler;
import com.flazr.rtmp.client.ClientOptions;
import com.flazr.rtmp.message.AbstractMessage;
import com.flazr.rtmp.message.Audio;
import com.flazr.rtmp.message.BytesRead;
import com.flazr.rtmp.message.Command;
import com.flazr.rtmp.message.CommandAmf0;
import com.flazr.rtmp.message.Control;
import com.flazr.rtmp.message.Metadata;
import com.flazr.rtmp.message.SetPeerBw;
import com.flazr.rtmp.message.WindowAckSize;
import com.flazr.rtmp.server.ServerStream.PublishType;

public abstract class VoiceConnection extends RtmpConnection {

    private static final Logger log = LoggerFactory.getLogger(VoiceConnection.class);
	private String publishName;
	private String playName;
	@SuppressWarnings("unused")
	private String codec;
	private int playStreamId = -1;
	private int publishStreamId = -1;
    
	public VoiceConnection(ClientOptions options, BigBlueButtonClient context) {
		super(options, context);
	}
	
	@Override
	protected ClientBootstrap getBootstrap(Executor executor) {
        final ChannelFactory factory = new NioClientSocketChannelFactory(executor, executor);
        final ClientBootstrap bootstrap = new ClientBootstrap(factory);
        bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
			@Override
			public ChannelPipeline getPipeline() throws Exception {
		        final ChannelPipeline pipeline = Channels.pipeline();
		        pipeline.addLast("handshaker", new ClientHandshakeHandler(options));
		        pipeline.addLast("decoder", new RtmpDecoder());
		        pipeline.addLast("encoder", new RtmpEncoder());
		        pipeline.addLast("handler", VoiceConnection.this);
		        return pipeline;
			}
		});
        bootstrap.setOption("tcpNoDelay" , true);
        bootstrap.setOption("keepAlive", true);
        return bootstrap;
	}

	@Override
	public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) {
        Amf0Object object = AbstractMessage.object(
                AbstractMessage.pair("app", options.getAppName()),
                AbstractMessage.pair("flashVer", "LNX 11,1,102,55"),
                AbstractMessage.pair("tcUrl", options.getTcUrl()),
                AbstractMessage.pair("fpad", false),
                AbstractMessage.pair("capabilities", 239.0),
                AbstractMessage.pair("audioCodecs", 3575.0),
                AbstractMessage.pair("videoCodecs", 252.0),
                AbstractMessage.pair("videoFunction", 1.0),
                AbstractMessage.pair("objectEncoding", 0.0)
        );
        log.debug(object.toString());
        
        /*
         * https://github.com/bigbluebutton/bigbluebutton/blob/master/bigbluebutton-client/src/org/bigbluebutton/modules/phone/managers/ConnectionManager.as#L78
         * netConnection.connect(uri, externUID, username);
         */
        JoinedMeeting meeting = context.getJoinService().getJoinedMeeting();
        Command connect = new CommandAmf0("connect", object, 
    			meeting.getExternUserID(),
        		context.getMyUserId() + "-" + meeting.getFullname());
        
        writeCommandExpectingResult(e.getChannel(), connect);
	}
	
	@Override
	public void channelDisconnected(ChannelHandlerContext ctx,
			ChannelStateEvent e) throws Exception {
		super.channelDisconnected(ctx, e);
		log.debug("Rtmp Channel Disconnected");
	}

    @SuppressWarnings("unchecked")
	public String connectGetCode(Command command) {
    	return ((Map<String, Object>) command.getArg(0)).get("code").toString();
    }
    
    // netConnection.call("voiceconf.call", null, "default", username, dialStr);
    public void call(Channel channel) {
    	Command command = new CommandAmf0("voiceconf.call", null, 
    			"default",
    			context.getJoinService().getJoinedMeeting().getFullname(), 
    			context.getJoinService().getJoinedMeeting().getWebvoiceconf());
    	writeCommandExpectingResult(channel, command);
    }
    
	// https://github.com/bigbluebutton/bigbluebutton/blob/master/bigbluebutton-client/src/org/bigbluebutton/modules/phone/managers/ConnectionManager.as#L149
    public boolean onCall(String resultFor, Command command) {
    	if (resultFor.equals("voiceconf.call")) {
    		log.debug(command.toString());
    		return true;
    	} else
    		return false;
    }
    
    public void hangup(Channel channel) {
    	Command command = new CommandAmf0("voiceconf.hangup", null, 
    			"default");
    	writeCommandExpectingResult(channel, command);
    }
    
	@Override
	public void messageReceived(ChannelHandlerContext ctx, MessageEvent me) {
        final Channel channel = me.getChannel();
        final RtmpMessage message = (RtmpMessage) me.getMessage();
        switch(message.getHeader().getMessageType()) {
        	case CONTROL:
                Control control = (Control) message;
                switch(control.getType()) {
                    case PING_REQUEST:
                        final int time = control.getTime();
                        Control pong = Control.pingResponse(time);
                        channel.write(pong);
                        break;
                }
        		break;
        	
            case METADATA_AMF0:
            case METADATA_AMF3:
                Metadata metadata = (Metadata) message;
                if(!metadata.getName().equals("onMetaData"))
                	log.debug("ignoring metadata: {}", metadata);
                break;

            case COMMAND_AMF0:
	        case COMMAND_AMF3:
	            Command command = (Command) message;                
	            String name = command.getName();
	            log.debug("server command: {}", name);
	            if(name.equals("_result")) {
	                String resultFor = transactionToCommandMap.get(command.getTransactionId());
	                if (resultFor == null) {
	                	log.warn("result for method without tracked transaction");
	                	break;
	                }
	                log.info("result for method call: {}", resultFor);
	                if(resultFor.equals("connect")) {
	                	String code = connectGetCode(command);
	                	if (code.equals("NetConnection.Connect.Success")) {
	                		call(channel);
	                	} else {
	                		log.error("method connect result in {}, quitting", code);
	                		log.debug("connect response: {}", command.toString());
	                		channel.close();
	                	}
	                	return;
                    } else if(resultFor.equals("createStream")) {
                    	if (playStreamId == -1) {
                    		playStreamId = ((Double) command.getArg(0)).intValue();
                            log.debug("playStreamId to use: {}", streamId);
                    		options.setStreamName(playName);
							channel.write(Command.play(playStreamId, options));
						  	channel.write(Control.setBuffer(streamId, 0));
                    	} else if (publishStreamId == -1) {
                    		publishStreamId = ((Double) command.getArg(0)).intValue();
                            log.debug("publishStreamId to use: {}", streamId);
                            options.setStreamName(publishName);
                            options.setPublishType(PublishType.LIVE);
                            channel.write(Command.publish(publishStreamId, options));
                    	}
	                } else if (onCall(resultFor, command)) {
	                	break;
	                } else {
	                	log.info("ignoring result: {}", message);
	                }
	            } else if (name.equals("successfullyJoinedVoiceConferenceCallback")) {
	            	onSuccessfullyJoined(command);
	            	writeCommandExpectingResult(channel, Command.createStream());
	            	writeCommandExpectingResult(channel, Command.createStream());
	            } else if (name.equals("disconnectedFromJoinVoiceConferenceCallback")) {
	            	onDisconnectedFromJoin(command);
	            	channel.close();
	            } else if (name.equals("failedToJoinVoiceConferenceCallback")) {
	            	onFailedToJoin(command);
	            	channel.close();
	            }
	            break;
	        case AUDIO:
                bytesRead += message.getHeader().getSize();
                if((bytesRead - bytesReadLastSent) > bytesReadWindow) {
                    logger.debug("sending bytes read ack {}", bytesRead);
                    bytesReadLastSent = bytesRead;
                    channel.write(new BytesRead(bytesRead));
                }
                onAudio((Audio) message);
	        	break;
	        case SHARED_OBJECT_AMF0:
	        case SHARED_OBJECT_AMF3:
	        	onSharedObject(channel, (SharedObjectMessage) message);
	        	break;
            case WINDOW_ACK_SIZE:
                WindowAckSize was = (WindowAckSize) message;                
                if(was.getValue() != bytesReadWindow) {
                    channel.write(SetPeerBw.dynamic(bytesReadWindow));
                }                
                break;
            case SET_PEER_BW:
                SetPeerBw spb = (SetPeerBw) message;                
                if(spb.getValue() != bytesWrittenWindow) {
                    channel.write(new WindowAckSize(bytesWrittenWindow));
                }
                break;
    		default:
    			log.info("ignoring rtmp message: {}", message);
	        	break;
        }
	}
	
	private void onFailedToJoin(Command command) {
		// TODO Auto-generated method stub
		
	}

	/*
	 * 14:42:18,175 [New I/O client worker #2-1] DEBUG [com.flazr.amf.Amf0Value] - << [STRING disconnectedFromJoinVoiceConferenceCallback]
	 * 14:42:18,175 [New I/O client worker #2-1] DEBUG [com.flazr.amf.Amf0Value] - << [NUMBER 4.0]
	 * 14:42:18,175 [New I/O client worker #2-1] DEBUG [com.flazr.amf.Amf0Value] - << [NULL null]
	 * 14:42:18,176 [New I/O client worker #2-1] DEBUG [com.flazr.amf.Amf0Value] - << [STRING onUaCallClosed]
	 * 14:42:18,176 [New I/O client worker #2-1] DEBUG [org.mconf.bbb.phone.VoiceConnection] - server command: disconnectedFromJoinVoiceConferenceCallback
	 */
	private void onDisconnectedFromJoin(Command command) {
		@SuppressWarnings("unused")
		String message = (String) command.getArg(0);
	}

	/*
	 * 14:27:38,282 [New I/O client worker #2-1] DEBUG [com.flazr.amf.Amf0Value] - << [STRING successfullyJoinedVoiceConferenceCallback]
	 * 14:27:38,282 [New I/O client worker #2-1] DEBUG [com.flazr.amf.Amf0Value] - << [NUMBER 3.0]
	 * 14:27:38,282 [New I/O client worker #2-1] DEBUG [com.flazr.amf.Amf0Value] - << [NULL null]
	 * 14:27:38,282 [New I/O client worker #2-1] DEBUG [com.flazr.amf.Amf0Value] - << [STRING microphone_1322327135253]
	 * 14:27:38,282 [New I/O client worker #2-1] DEBUG [com.flazr.amf.Amf0Value] - << [STRING speaker_1322327135251]
	 * 14:27:38,282 [New I/O client worker #2-1] DEBUG [com.flazr.amf.Amf0Value] - << [STRING SPEEX]
	 * 14:27:38,282 [New I/O client worker #2-1] DEBUG [org.mconf.bbb.phone.VoiceConnection] - server command: successfullyJoinedVoiceConferenceCallback
	 */
	private void onSuccessfullyJoined(Command command) {
		publishName = (String) command.getArg(0);
		playName = (String) command.getArg(1);
		codec = (String) command.getArg(2);
	}
	
	abstract protected void onAudio(Audio audio);
}
