package org.red5.server.stream;

/*
 * RED5 Open Source Flash Server - http://www.osflash.org/red5
 * 
 * Copyright (c) 2006 by respective authors (see below). All rights reserved.
 * 
 * This library is free software; you can redistribute it and/or modify it under the 
 * terms of the GNU Lesser General Public License as published by the Free Software 
 * Foundation; either version 2.1 of the License, or (at your option) any later 
 * version. 
 * 
 * This library is distributed in the hope that it will be useful, but WITHOUT ANY 
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A 
 * PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License along 
 * with this library; if not, write to the Free Software Foundation, Inc., 
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA 
 */

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.red5.server.api.IConnection;
import org.red5.server.api.IScope;
import org.red5.server.api.ScopeUtils;
import org.red5.server.api.event.IEvent;
import org.red5.server.api.event.IEventDispatcher;
import org.red5.server.api.event.IEventListener;
import org.red5.server.api.stream.*;
import org.red5.server.api.stream.IStreamFilenameGenerator.GenerationType;
import org.red5.server.messaging.*;
import org.red5.server.net.rtmp.event.*;
import org.red5.server.net.rtmp.status.Status;
import org.red5.server.net.rtmp.status.StatusCodes;
import org.red5.server.stream.codec.StreamCodecInfo;
import org.red5.server.stream.consumer.FileConsumer;
import org.red5.server.stream.message.RTMPMessage;
import org.red5.server.stream.message.StatusMessage;
import org.springframework.core.io.Resource;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Represents live stream broadcasted from client. As Flash Media Server, Red5 supports
 * recording mode for live streams, that is, broadcasted stream has broadcast mode. It can be either
 * "live" or "record" and latter causes server-side application to record broadcasted stream.
 *
 * Note that recorded streams are recorded as FLV files. The same is correct for audio, because
 * NellyMoser codec that Flash Player uses prohibits on-the-fly transcoding to audio formats like MP3
 * without paying of licensing fee or buying SDK.
 *
 * This type of stream uses two different pipes for live streaming and recording.
 */
public class ClientBroadcastStream extends AbstractClientStream implements
		IClientBroadcastStream, IFilter, IPushableConsumer,
		IPipeConnectionListener, IEventDispatcher {

    /**
     * Logger
     */
    private static final Log log = LogFactory
			.getLog(ClientBroadcastStream.class);
    /**
     * Stream published name
     */
	private String publishedName;
    /**
     * Output endpoint that providers use
     */
	private IMessageOutput connMsgOut;
    /**
     * Factory object for video codecs
     */
	private VideoCodecFactory videoCodecFactory = null;
    /**
     * Is there need to check video codec?
     */
	private boolean checkVideoCodec = false;
    /**
     * Pipe for live streaming
     */
	private IPipe livePipe;
    /**
     * Pipe for recording
     */
	private IPipe recordPipe;
    /**
     * Whether we are recording or not
     */
	private boolean recording = false;
    /**
     * Is there need to send start notification?
     */
	private boolean sendStartNotification = true;

	/** Stores absolute time for video stream. */
	private int audioTime = -1;

	/** Stores absolute time for audio stream. */
	private int videoTime = -1;

	/** Stores absolute time for data stream. */
	private int dataTime = -1;

	/** Stores timestamp of first packet. */
	private int firstTime = -1;

    /**
     * Data is sent by chunks, each of them has size
     */
    private int chunkSize = 0;

	/**
     * Starts stream. Creates pipes, video codec from video codec factory bean,
     * connects 
     */
    public void start() {
		IConsumerService consumerManager = (IConsumerService) getScope()
				.getContext().getBean(IConsumerService.KEY);
		try {
			videoCodecFactory = (VideoCodecFactory) getScope().getContext()
					.getBean(VideoCodecFactory.KEY);
			checkVideoCodec = true;
		} catch (Exception err) {
			log.warn("No video codec factory available.", err);
		}
		firstTime = audioTime = videoTime = dataTime = -1;
		connMsgOut = consumerManager.getConsumerOutput(this);
		recordPipe = new InMemoryPushPushPipe();
		Map<Object, Object> recordParamMap = new HashMap<Object, Object>();
        // Clear record flag
        recordParamMap.put("record", null);
		recordPipe.subscribe((IProvider) this, recordParamMap);
		recording = false;
		setCodecInfo(new StreamCodecInfo());
	}

	/**
     * Closes stream, unsubscribes provides, sends stoppage notifications and broadcast close notification.
     */
    public void close() {
		if (livePipe != null) {
			livePipe.unsubscribe((IProvider) this);
		}
		recordPipe.unsubscribe((IProvider) this);
		if (recording)
			sendRecordStopNotify();
		else
			sendPublishStopNotify();
		notifyBroadcastClose();
	}

    /**
     * Save broadcasted stream.
     *
     * @param name                           Stream name
     * @param isAppend                       Append mode
     * @throws ResourceNotFoundException     Resource doesn't exist when trying to append.
     * @throws ResourceExistException        Resource exist when trying to create.
     */
    public void saveAs(String name, boolean isAppend)
            throws ResourceNotFoundException, ResourceExistException {
		try {
            // Get stream scope
            IScope scope = getConnection().getScope();
            // Get stream filename generator
            IStreamFilenameGenerator generator = (IStreamFilenameGenerator) ScopeUtils
					.getScopeService(scope, IStreamFilenameGenerator.class,
							DefaultStreamFilenameGenerator.class);

            // Generate filename
            String filename = generator.generateFilename(scope, name, ".flv", GenerationType.RECORD);
            // Get resource for that filename
            Resource res = scope.getContext().getResource(filename);
            // If append mode is on...
            if (!isAppend) {
				if (res.exists()) {
					// Per livedoc of FCS/FMS:
					// When "live" or "record" is used,
					// any previously recorded stream with the same stream URI is deleted.
					res.getFile().delete();
				}
			} else {
				if (!res.exists()) {
					// Per livedoc of FCS/FMS:
					// If a recorded stream at the same URI does not already exist,
					// "append" creates the stream as though "record" was passed.
					isAppend = false;
				}
			}

			if (!res.exists()) {
				// Make sure the destination directory exists
				try {
					String path = res.getFile().getAbsolutePath();
					int slashPos = path.lastIndexOf(File.separator);
					if (slashPos != -1) {
						path = path.substring(0, slashPos);
					}
					File tmp = new File(path);
					if (!tmp.isDirectory()) {
						tmp.mkdirs();
					}
				} catch (IOException err) {
					log.error("Could not create destination directory.", err);
				}
				res = scope.getResource(filename);
			}

			if (!res.exists()) {
				res.getFile().createNewFile();
			}
			FileConsumer fc = new FileConsumer(scope, res.getFile());
			Map<Object, Object> paramMap = new HashMap<Object, Object>();
			if (isAppend) {
				paramMap.put("mode", "append");
			} else {
				paramMap.put("mode", "record");
			}
			recordPipe.subscribe(fc, paramMap);
			recording = true;
		} catch (IOException e) {
        }
	}

    /**
     * Getter for provider
     * @return            Provider
     */
    public IProvider getProvider() {
		return this;
	}

    /**
     * Getter for published name
     * @return        Stream published name
     */
    public String getPublishedName() {
		return publishedName;
	}

    /**
     * Setter for stream published name
     * @param name       Name that used for publishing. Set at client side when begin to broadcast with NetStream#publish.
     */
    public void setPublishedName(String name) {
		this.publishedName = name;
	}

    /**
     * Currently not implemented
     *
     * @param pipe           Pipe
     * @param message        Message
     */
    public void pushMessage(IPipe pipe, IMessage message) {
	}

    /**
     * Send OOB control message with chunk size
     */
    private void notifyChunkSize() {
		if (chunkSize > 0 && livePipe != null) {
			OOBControlMessage setChunkSize = new OOBControlMessage();
			setChunkSize.setTarget("ConnectionConsumer");
			setChunkSize.setServiceName("chunkSize");
			if (setChunkSize.getServiceParamMap() == null) {
				setChunkSize.setServiceParamMap(new HashMap());
			}
			setChunkSize.getServiceParamMap().put("chunkSize", chunkSize);
			livePipe.sendOOBControlMessage(getProvider(), setChunkSize);
		}
	}

    /**
     * Out-of-band control message handler
     *
     * @param source           OOB message source
     * @param pipe             Pipe that used to send OOB message
     * @param oobCtrlMsg       Out-of-band control message
     */
    public void onOOBControlMessage(IMessageComponent source, IPipe pipe,
			OOBControlMessage oobCtrlMsg) {
		if (!"ClientBroadcastStream".equals(oobCtrlMsg.getTarget())) {
			return;
		}

		if ("chunkSize".equals(oobCtrlMsg.getServiceName())) {
			chunkSize = (Integer) oobCtrlMsg.getServiceParamMap().get(
					"chunkSize");
			notifyChunkSize();
		}
	}

    /**
     * Dispatches event
     * @param event          Event to dispatch
     */
    public void dispatchEvent(IEvent event) {
		if (!(event instanceof IRTMPEvent)
                        && (event.getType() != IEvent.Type.STREAM_CONTROL)
                        && (event.getType() != IEvent.Type.STREAM_DATA)) {
			return;
		}

		IStreamCodecInfo codecInfo = getCodecInfo();
		StreamCodecInfo streamCodec = null;
		if (codecInfo instanceof StreamCodecInfo) {
			streamCodec = (StreamCodecInfo) codecInfo;
		}

        IRTMPEvent rtmpEvent = null;
        try {
            rtmpEvent = (IRTMPEvent) event;
        } catch (ClassCastException e) {
            e.printStackTrace();
            log.error("Class cast exception in event dispatch", e);
            return;
        }
        int thisTime = -1;
		if (firstTime == -1) {
			firstTime = rtmpEvent.getTimestamp();
		}
		if (rtmpEvent instanceof AudioData) {
			if (streamCodec != null) {
				streamCodec.setHasAudio(true);
			}
			if (rtmpEvent.getHeader().isTimerRelative()) {
				audioTime += rtmpEvent.getTimestamp();
			} else {
				audioTime = rtmpEvent.getTimestamp();
			}
			thisTime = audioTime;
		} else if (rtmpEvent instanceof VideoData) {
			IVideoStreamCodec videoStreamCodec = null;
			if (videoCodecFactory != null && checkVideoCodec) {
				videoStreamCodec = videoCodecFactory
						.getVideoCodec(((VideoData) rtmpEvent).getData());
				if (codecInfo instanceof StreamCodecInfo) {
					((StreamCodecInfo) codecInfo)
							.setVideoCodec(videoStreamCodec);
				}
				checkVideoCodec = false;
			} else if (codecInfo != null) {
				videoStreamCodec = codecInfo.getVideoCodec();
			}

			if (videoStreamCodec != null) {
				videoStreamCodec.addData(((VideoData) rtmpEvent).getData());
			}

			if (streamCodec != null) {
				streamCodec.setHasVideo(true);
			}
			if (rtmpEvent.getHeader().isTimerRelative()) {
				videoTime += rtmpEvent.getTimestamp();
			} else {
				videoTime = rtmpEvent.getTimestamp();
			}
			thisTime = videoTime;
		} else if(rtmpEvent instanceof Invoke) {
			if (rtmpEvent.getHeader().isTimerRelative()) {
				dataTime += rtmpEvent.getTimestamp();
			} else {
				dataTime = rtmpEvent.getTimestamp();
			}
			return;
		} else if (rtmpEvent instanceof Notify) {
			if (rtmpEvent.getHeader().isTimerRelative()) {
				dataTime += rtmpEvent.getTimestamp();
			} else {
				dataTime = rtmpEvent.getTimestamp();
			}
			thisTime = dataTime;
		}
		checkSendNotifications(event);

		RTMPMessage msg = new RTMPMessage();
		msg.setBody(rtmpEvent);
		msg.getBody().setTimestamp(thisTime);
		if (livePipe != null) {
			livePipe.pushMessage(msg);
		}
		recordPipe.pushMessage(msg);
	}

    /**
     * Check send notification
     * @param event          Event
     */
    private void checkSendNotifications(IEvent event) {
		IEventListener source = event.getSource();
		if (sendStartNotification) {
			// Notify handler that stream starts recording/publishing
			sendStartNotification = false;
			if (source instanceof IConnection) {
				IScope scope = ((IConnection) source).getScope();
				if (scope.hasHandler()) {
					Object handler = scope.getHandler();
					if (handler instanceof IStreamAwareScopeHandler) {
						if (recording) {
							((IStreamAwareScopeHandler) handler).streamRecordStart(this);
						} else {
							((IStreamAwareScopeHandler) handler).streamPublishStart(this);
						}
					}
				}
			}
			// Send start notifications
			if (recording) {
				sendRecordStartNotify();
			} else
				sendPublishStartNotify();
			notifyBroadcastStart();
		}
	}

    /**
     * Pipe connection event handler
     * @param event          Pipe connection event
     */
    public void onPipeConnectionEvent(PipeConnectionEvent event) {
		switch (event.getType()) {
			case PipeConnectionEvent.PROVIDER_CONNECT_PUSH:
				if (event.getProvider() == this
						&& (event.getParamMap() == null || !event.getParamMap()
								.containsKey("record"))) {
					this.livePipe = (IPipe) event.getSource();
				}
				break;
			case PipeConnectionEvent.PROVIDER_DISCONNECT:
				if (this.livePipe == event.getSource()) {
					this.livePipe = null;
				}
				break;
			case PipeConnectionEvent.CONSUMER_CONNECT_PUSH:
				if (this.livePipe == event.getSource()) {
					notifyChunkSize();
				}
				break;
			default:
				break;
		}
	}

    /**
     * Sends publish start notifications
     */
    private void sendPublishStartNotify() {
		Status start = new Status(StatusCodes.NS_PUBLISH_START);
		start.setClientid(getStreamId());
		start.setDetails(getPublishedName());

		StatusMessage startMsg = new StatusMessage();
		startMsg.setBody(start);
		connMsgOut.pushMessage(startMsg);
	}

    /**
     *  Sends publish stop notifications
     */
	private void sendPublishStopNotify() {
		Status stop = new Status(StatusCodes.NS_UNPUBLISHED_SUCCESS);
		stop.setClientid(getStreamId());
		stop.setDetails(getPublishedName());

		StatusMessage stopMsg = new StatusMessage();
		stopMsg.setBody(stop);
		connMsgOut.pushMessage(stopMsg);
	}

    /**
     *  Sends record start notifications
     */
	private void sendRecordStartNotify() {
		Status start = new Status(StatusCodes.NS_RECORD_START);
		start.setClientid(getStreamId());
		start.setDetails(getPublishedName());

		StatusMessage startMsg = new StatusMessage();
		startMsg.setBody(start);
		connMsgOut.pushMessage(startMsg);
	}

    /**
     *  Sends record stop notifications
     */
	private void sendRecordStopNotify() {
		Status start = new Status(StatusCodes.NS_RECORD_STOP);
		start.setClientid(getStreamId());
		start.setDetails(getPublishedName());

		StatusMessage startMsg = new StatusMessage();
		startMsg.setBody(start);
		connMsgOut.pushMessage(startMsg);
	}

    /**
     *  Notifies handler on stream broadcast start
     */
	private void notifyBroadcastStart() {
		IStreamAwareScopeHandler handler = getStreamAwareHandler();
		if (handler != null) {
			try {
				handler.streamBroadcastStart(this);
			} catch (Throwable t) {
				log.error("error notify streamBroadcastStart", t);
			}
		}
	}

    /**
     *  Notifies handler on stream broadcast stop
     */
	private void notifyBroadcastClose() {
		IStreamAwareScopeHandler handler = getStreamAwareHandler();
		if (handler != null) {
			try {
				handler.streamBroadcastClose(this);
			} catch (Throwable t) {
				log.error("error notify streamBroadcastStop", t);
			}
		}
	}
}