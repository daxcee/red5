package org.red5.server.net.rtmp;

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
import org.apache.mina.common.ByteBuffer;
import org.red5.server.BaseConnection;
import org.red5.server.api.IBandwidthConfigure;
import org.red5.server.api.IContext;
import org.red5.server.api.IFlowControllable;
import org.red5.server.api.Red5;
import static org.red5.server.api.ScopeUtils.getScopeService;
import org.red5.server.api.scheduling.IScheduledJob;
import org.red5.server.api.scheduling.ISchedulingService;
import org.red5.server.api.service.IPendingServiceCall;
import org.red5.server.api.service.IPendingServiceCallback;
import org.red5.server.api.service.IServiceCall;
import org.red5.server.api.service.IServiceCapableConnection;
import org.red5.server.api.stream.*;
import org.red5.server.net.rtmp.event.*;
import org.red5.server.net.rtmp.message.Packet;
import org.red5.server.service.Call;
import org.red5.server.service.PendingCall;
import org.red5.server.stream.*;
import org.springframework.context.ApplicationContext;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;

/**
 * RTMP connection. Stores information about client streams, data transfer channels,
 * pending RPC calls, bandwidth configuration, used encoding (AMF0/AMF3), connection state (is alive, last
 * ping time and ping result) and session.
 */
public abstract class RTMPConnection extends BaseConnection implements
		IStreamCapableConnection, IServiceCapableConnection {
    /**
     * Logger
     */
	protected static Log log = LogFactory
			.getLog(RTMPConnection.class.getName());

    /**
     * Streams limit
     */
    protected static final int MAX_STREAMS = 12;

    /**
     * Video codec factory constant
     */
    private static final String VIDEO_CODEC_FACTORY = "videoCodecFactory";

	// private Context context;

    /**
     * Connection channels
     *
     * @see org.red5.server.net.rtmp.Channel
     */
    private Channel[] channels = new Channel[64];

    /**
     * Client streams
     *
     * @see org.red5.server.api.stream.IClientStream
     */
    private IClientStream[] streams = new IClientStream[MAX_STREAMS];

	private boolean[] reservedStreams = new boolean[MAX_STREAMS];

    /**
     * Identifier for remote calls
     */
    protected Integer invokeId = 1;

    /**
     * Hash map that stores pending calls and ids as pairs.
     */
    protected HashMap<Integer, IPendingServiceCall> pendingCalls = new HashMap<Integer, IPendingServiceCall>();

    /**
     * Deferred results set
     *
     * @see org.red5.server.net.rtmp.DeferredResult
     */
    protected HashSet<DeferredResult> deferredResults = new HashSet<DeferredResult>();

    /**
     * Last ping timestamp
     */
    protected int lastPingTime = -1;

    /**
     * Whether ping replied or not
     */
    protected boolean pingReplied = true;

    /**
     * Name of quartz job that keeps connection alive
     */
    protected String keepAliveJobName;

    /**
     * Keep alive interval
     */
    protected int keepAliveInterval = 1000;

    /**
     * Data read interval
     */
    private int bytesReadInterval = 125000;

    /**
     * Number of bytes to read next
     */
    private int nextBytesRead = 125000;

    /**
     * Bandwidth configuration
     *
     * @see org.red5.server.api.IBandwidthConfigure
     */
    private IBandwidthConfigure bandwidthConfig;

    /**
     * Map for pending video packets and stream IDs
     */
    private Map<Integer, Integer> pendingVideos = new HashMap<Integer, Integer>();

    /**
     * Number of streams used
     */
    private int usedStreams;

    /**
     * AMF version, AMF0 by default
     */
    protected Encoding encoding = Encoding.AMF0;

    /**
     * Creates anonymous RTMP connection without scope
     * @param type          Connection type
     */
    public RTMPConnection(String type) {
		// We start with an anonymous connection without a scope.
		// These parameters will be set during the call of "connect" later.
		// super(null, ""); temp fix to get things to compile
		super(type, null, null, 0, null, null, null);
	}

    /**
     * Initialize connection
     *
     * @param host             Connection host
     * @param path             Connection path
     * @param sessionId        Connection session id
     * @param params           Params passed from client
     */
    public void setup(String host, String path, String sessionId,
			Map<String, Object> params) {
		this.host = host;
		this.path = path;
		this.sessionId = sessionId;
		this.params = params;
		if (params.get("objectEncoding") == Integer.valueOf(3))
			encoding = Encoding.AMF3;
	}

    /**
     * Return AMF protocol encoding used by this connection
     * @return                  AMF encoding used by connection
     */
    public Encoding getEncoding() {
		return encoding;
	}
	/**
     * Getter for  next available channel id
     *
     * @return  Next available channel id
     */
	public int getNextAvailableChannelId() {
		int result = -1;
		for (byte i = 4; i < channels.length; i++) {
			if (!isChannelUsed(i)) {
				result = i;
				break;
			}
		}
		return result;
	}

    /**
     * Checks whether channel is used
     * @param channelId        Channel id
     * @return                 <code>true</code> if channel is in use, <code>false</code> otherwise
     */
    public boolean isChannelUsed(byte channelId) {
		return (channels[channelId] != null);
	}

    /**
     * Return channel by id
     * @param channelId        Channel id
     * @return                 Channel by id
     */
    public Channel getChannel(byte channelId) {
		if (!isChannelUsed(channelId)) {
			channels[channelId] = new Channel(this, channelId);
		}
		return channels[channelId];
	}

    /**
     * Closes channel
     * @param channelId       Channel id
     */
    public void closeChannel(byte channelId) {
		channels[channelId] = null;
	}

	/**
     * Getter for client streams
     *
     * @return  Client streams as array
     */
    protected IClientStream[] getStreams() {
		return streams;
	}

	/** {@inheritDoc} */
    public int reserveStreamId() {
		int result = -1;
		synchronized (reservedStreams) {
			for (int i = 0; i < reservedStreams.length; i++) {
				if (!reservedStreams[i]) {
					reservedStreams[i] = true;
					result = i;
					break;
				}
			}
		}
		return result + 1;
	}

    /**
     * Creates output stream object from stream id. Output stream consists of audio, data and video channels.
     *
     * @see   org.red5.server.stream.OutputStream
     * @param streamId          Stream id
     * @return                  Output stream object
     */
    public OutputStream createOutputStream(int streamId) {
		byte channelId = (byte) (4 + ((streamId - 1) * 5));
		final Channel data = getChannel(channelId++);
		final Channel video = getChannel(channelId++);
		final Channel audio = getChannel(channelId++);
		// final Channel unknown = getChannel(channelId++);
		// final Channel ctrl = getChannel(channelId++);
		return new OutputStream(video, audio, data);
	}

	/**
     * Getter for  video codec factory
     *
     * @return  Video codec factory
     */
    public VideoCodecFactory getVideoCodecFactory() {
		final IContext context = scope.getContext();
		ApplicationContext appCtx = context.getApplicationContext();
		if (!appCtx.containsBean(VIDEO_CODEC_FACTORY)) {
			return null;
		}

		return (VideoCodecFactory) appCtx.getBean(VIDEO_CODEC_FACTORY);
	}

	/** {@inheritDoc} */
    public IClientBroadcastStream newBroadcastStream(int streamId) {
		if (!reservedStreams[streamId - 1]) {
			// StreamId has not been reserved before
			return null;
		}

		synchronized (streams) {
			if (streams[streamId - 1] != null) {
				// Another stream already exists with this id
				return null;
			}
			ApplicationContext appCtx =
				scope.getContext().getApplicationContext();
			ClientBroadcastStream cbs = (ClientBroadcastStream)
				appCtx.getBean("clientBroadcastStream");
			/**
			 * Picking up the ClientBroadcastStream defined as a spring prototype
			 * in red5-common.xml
			 */
			cbs.setStreamId(streamId);
			cbs.setConnection(this);
			cbs.setName(createStreamName());
			cbs.setScope(this.getScope());

			streams[streamId - 1] = cbs;
			usedStreams++;
			return cbs;
		}
	}

    /** {@inheritDoc}
     * To be implemented.
     */
    public ISingleItemSubscriberStream newSingleItemSubscriberStream(
			int streamId) {
		// TODO implement it
		return null;
	}

	/** {@inheritDoc} */
    public IPlaylistSubscriberStream newPlaylistSubscriberStream(int streamId) {
		if (!reservedStreams[streamId - 1]) {
			// StreamId has not been reserved before
			return null;
		}

		synchronized (streams) {
			if (streams[streamId - 1] != null) {
				// Another stream already exists with this id
				return null;
			}
			ApplicationContext appCtx =
				scope.getContext().getApplicationContext();
			/**
			 * Picking up the PlaylistSubscriberStream defined as a spring prototype
			 * in red5-common.xml
			 */
			PlaylistSubscriberStream pss = (PlaylistSubscriberStream)
				appCtx.getBean("playlistSubscriberStream");
			pss.setName(createStreamName());
			pss.setConnection(this);
			pss.setScope(this.getScope());
			pss.setStreamId(streamId);
			streams[streamId - 1] = pss;
			usedStreams++;
			return pss;
		}
	}

	/**
     * Getter for used stream count
     *
     * @return Value for property 'usedStreamCount'.
     */
    protected int getUsedStreamCount() {
		return usedStreams;
	}

	/** {@inheritDoc} */
    public IClientStream getStreamById(int id) {
		if (id <= 0 || id > MAX_STREAMS - 1) {
			return null;
		}

		return streams[id - 1];
	}

    /**
     * Return stream id for given channel id
     * @param channelId        Channel id
     * @return                 ID of stream that channel belongs to
     */
    public int getStreamIdForChannel(byte channelId) {
		if (channelId < 4) {
			return 0;
		}

		return (int) Math.floor((channelId - 4) / 5) + 1;
	}

    /**
     * Return stream for given channel id
     * @param channelId        Channel id
     * @return                 Stream that channel belongs to
     */
    public IClientStream getStreamByChannelId(byte channelId) {
		if (channelId < 4) {
			return null;
		}

		return streams[getStreamIdForChannel(channelId) - 1];
	}

	/** {@inheritDoc} */
    @Override
	public void close() {
		if (keepAliveJobName != null) {
			ISchedulingService schedulingService =
				(ISchedulingService) getScope().getContext().getBean(ISchedulingService.BEAN_NAME);
			schedulingService.removeScheduledJob(keepAliveJobName);
			keepAliveJobName = null;
		}
		Red5.setConnectionLocal(this);
		IStreamService streamService = (IStreamService) getScopeService(scope,
				IStreamService.class, StreamService.class);
		if (streamService != null) {
			synchronized (streams) {
				for (int i = 0; i < streams.length; i++) {
					IClientStream stream = streams[i];
					if (stream != null) {
						if (log.isDebugEnabled()) {
							log.debug("Closing stream: " + stream.getStreamId());
						}
						streamService.deleteStream(this, stream.getStreamId());
						streams[i] = null;
						usedStreams--;
					}
				}
			}
		}

		if (getScope() != null && getScope().getContext() != null) {
			IFlowControlService fcs = (IFlowControlService) getScope()
					.getContext().getBean(IFlowControlService.KEY);
			// XXX: Workaround for #54
			try {
				fcs.releaseFlowControllable(this);
			} catch (Exception err) {
				log.error("Could not release flowcontrollable connection "
						+ this, err);
			}
		}
		super.close();
	}

	/** {@inheritDoc} */
    public void unreserveStreamId(int streamId) {
		deleteStreamById(streamId);
		if (streamId > 0 && streamId <= MAX_STREAMS) {
			reservedStreams[streamId - 1] = false;
		}
	}

	/** {@inheritDoc} */
    public void deleteStreamById(int streamId) {
		if (streamId > 0 && streamId <= MAX_STREAMS) {
			if (streams[streamId - 1] != null) {
				synchronized (pendingVideos) {
					pendingVideos.remove(streamId);
				}
				usedStreams--;
				streams[streamId - 1] = null;
			}
		}
	}

    /**
     * Handler for ping event
     * @param ping        Ping event context
     */
    public void ping(Ping ping) {
		getChannel((byte) 2).write(ping);
	}

    /**
     * Write raw byte buffer
     * @param out           Byte buffer
     */
    public abstract void rawWrite(ByteBuffer out);

    /**
     * Write packet
     * @param out           Packet
     */
    public abstract void write(Packet out);

    /**
     * Update number of bytes to read next value
     */
    protected void updateBytesRead() {
		long bytesRead = getReadBytes();
		if (bytesRead >= nextBytesRead) {
			BytesRead sbr = new BytesRead((int) bytesRead);
			getChannel((byte) 2).write(sbr);
			log.info(sbr);
			nextBytesRead += bytesReadInterval;
		}
	}

    /**
     * Read number of recieved bytes
     * @param bytes                Number of bytes
     */
    public void receivedBytesRead(int bytes) {
		log.info("Client received " + bytes + " bytes, written "
				+ getWrittenBytes() + " bytes, " + getPendingMessages()
				+ " messages pending");
	}

	/** {@inheritDoc} */
    public void invoke(IServiceCall call) {
		invoke(call, (byte) 3);
	}

	/**
     * Generate next invoke id
     *
     * @return  Next invoke id for RPC
     */
    protected synchronized int getInvokeId() {
		return invokeId++;
	}

    /**
     * Register pending call (remote function call that is yet to finish)
     * @param invokeId             Deferred operation id
     * @param call                 Call service
     */
    protected void registerPendingCall(int invokeId, IPendingServiceCall call) {
		synchronized (pendingCalls) {
			pendingCalls.put(invokeId, call);
		}
	}

	/** {@inheritDoc} */
    public void invoke(IServiceCall call, byte channel) {
		// We need to use Invoke for all calls to the client
		Invoke invoke = new Invoke();
		invoke.setCall(call);
		invoke.setInvokeId(getInvokeId());
		if (call instanceof IPendingServiceCall) {
			registerPendingCall(invoke.getInvokeId(), (IPendingServiceCall) call);
		}
		getChannel(channel).write(invoke);
	}

	/** {@inheritDoc} */
    public void invoke(String method) {
		invoke(method, null, null);
	}

	/** {@inheritDoc} */
    public void invoke(String method, Object[] params) {
		invoke(method, params, null);
	}

	/** {@inheritDoc} */
    public void invoke(String method, IPendingServiceCallback callback) {
		invoke(method, null, callback);
	}

	/** {@inheritDoc} */
    public void invoke(String method, Object[] params,
			IPendingServiceCallback callback) {
		IPendingServiceCall call = new PendingCall(method, params);
		if (callback != null) {
			call.registerCallback(callback);
		}

		invoke(call);
	}

	/** {@inheritDoc} */
    public void notify(IServiceCall call) {
		notify(call, (byte) 3);
	}

	/** {@inheritDoc} */
    public void notify(IServiceCall call, byte channel) {
		Notify notify = new Notify();
		notify.setCall(call);
		getChannel(channel).write(notify);
	}

	/** {@inheritDoc} */
    public void notify(String method) {
		notify(method, null);
	}

	/** {@inheritDoc} */
    public void notify(String method, Object[] params) {
		IServiceCall call = new Call(method, params);
		notify(call);
	}

	/** {@inheritDoc} */
    public IBandwidthConfigure getBandwidthConfigure() {
		return bandwidthConfig;
	}

	/** {@inheritDoc} */
    public IFlowControllable getParentFlowControllable() {
		return this.getClient();
	}

	/** {@inheritDoc} */
    public void setBandwidthConfigure(IBandwidthConfigure config) {
		IFlowControlService fcs = (IFlowControlService) getScope().getContext()
				.getBean(IFlowControlService.KEY);
		this.bandwidthConfig = config;
		fcs.updateBWConfigure(this);

		// Notify client about new bandwidth settings (in bytes per second)
		if (config.getDownstreamBandwidth() > 0) {
			ServerBW serverBW = new ServerBW((int) config
					.getDownstreamBandwidth() / 8);
			getChannel((byte) 2).write(serverBW);
		}
		if (config.getUpstreamBandwidth() > 0) {
			ClientBW clientBW = new ClientBW((int) config
					.getUpstreamBandwidth() / 8, (byte) 0);
			getChannel((byte) 2).write(clientBW);
			// Update generation of BytesRead messages
			// TODO: what are the correct values here?
			bytesReadInterval = (int) config.getUpstreamBandwidth() / 8;
			nextBytesRead = (int) getWrittenBytes();
		}
	}

	/** {@inheritDoc} */
    @Override
	public long getReadBytes() {
		// TODO Auto-generated method stub
		return 0;
	}

	/** {@inheritDoc} */
    @Override
	public long getWrittenBytes() {
		// TODO Auto-generated method stub
		return 0;
	}

    /**
     * Get pending call service by id
     * @param invokeId               Pending call service id
     * @return                       Pending call service object
     */
    protected IPendingServiceCall getPendingCall(int invokeId) {
		IPendingServiceCall result;
		synchronized (pendingCalls) {
			result = pendingCalls.get(invokeId);
			if (result != null) {
				pendingCalls.remove(invokeId);
			}
		}
		return result;
	}

    /**
     * Generates new stream name
     * @return       New stream name
     */
    protected String createStreamName() {
		return UUID.randomUUID().toString();
	}

	/**
	 * Mark message as being written.
	 *
	 * @param message        Message to mark
	 */
	protected void writingMessage(Packet message) {
		if (message.getMessage() instanceof VideoData) {
			int streamId = message.getHeader().getStreamId();
			synchronized (pendingVideos) {
				Integer old = pendingVideos.get(streamId);
				if (old == null) {
					old = Integer.valueOf(0);
				}
				pendingVideos.put(streamId, old + 1);
			}
		}
	}

    /**
     * Increases number of read messages by one. Updates number of bytes read.
     */
    protected void messageReceived() {
		readMessages++;

		// Trigger generation of BytesRead messages
		updateBytesRead();
	}

	/**
	 * Mark message as sent.
	 *
	 * @param message           Message to mark
	 */
	protected void messageSent(Packet message) {
		if (message.getMessage() instanceof VideoData) {
			int streamId = message.getHeader().getStreamId();
			synchronized (pendingVideos) {
				Integer pending = pendingVideos.get(streamId);
				if (pending != null) {
					pendingVideos.put(streamId, pending - 1);
				}
			}
		}

		writtenMessages++;
	}

    /**
     * Increases number of dropped messages
     */
    protected void messageDropped() {
		droppedMessages++;
	}

	/** {@inheritDoc} */
    @Override
	public long getPendingVideoMessages(int streamId) {
		synchronized (pendingVideos) {
			Integer count = pendingVideos.get(streamId);
			long result = (count != null ? count.intValue()
					- getUsedStreamCount() : 0);
			return (result > 0 ? result : 0);
		}
	}

	/** {@inheritDoc} */
    public void ping() {
		Ping pingRequest = new Ping();
		pingRequest.setValue1((short) 6);
		int now = (int) (System.currentTimeMillis() & 0xffffffff);
		pingRequest.setValue2(now);
		pingRequest.setValue3(Ping.UNDEFINED);
		pingReplied = false;
		ping(pingRequest);
	}

    /**
     * Marks that pingback was recieved
     * @param pong            Ping object
     */
    protected void pingReceived(Ping pong) {
		pingReplied = true;
		int now = (int) (System.currentTimeMillis() & 0xffffffff);
		lastPingTime = now - pong.getValue2();
	}

	/** {@inheritDoc} */
    public int getLastPingTime() {
		return lastPingTime;
	}

	/**
     * Setter for keep alive interval
     *
     * @param keepAliveInterval Keep alive interval
     */
    public void setKeepAliveInterval(int keepAliveInterval) {
		this.keepAliveInterval = keepAliveInterval;
	}

    /**
     * Starts measurement
     */
    public void startRoundTripMeasurement() {
		ISchedulingService schedulingService =
			(ISchedulingService) getScope().getContext().getBean(ISchedulingService.BEAN_NAME);
		IScheduledJob keepAliveJob = new KeepAliveJob();
		keepAliveJobName = schedulingService.addScheduledJob(keepAliveInterval, keepAliveJob);
	}

    /**
     * Inactive state event handler
     */
    protected abstract void onInactive();

	/** {@inheritDoc} */
    @Override
	public String toString() {
		return getClass().getSimpleName() + " from " + getRemoteAddress() + ':'
				+ getRemotePort() + " to " + getHost() + " (in: "
				+ getReadBytes() + ", out: " + getWrittenBytes() + ')';
	}

    /**
     * Registers deffered result
     * @param result            Result to register
     */
    protected void registerDeferredResult(DeferredResult result) {
		deferredResults.add(result);
	}

    /**
     * Unregister deffered result
     * @param result             Result to unregister
     */
    protected void unregisterDeferredResult(DeferredResult result) {
		deferredResults.remove(result);
	}

    /**
     * Quartz job that keeps connection alive
     */
    private class KeepAliveJob implements IScheduledJob {
		/** {@inheritDoc} */
        public void execute(ISchedulingService service) {
			if (!pingReplied) {
				onInactive();
			}
			ping();
		}
	}
}