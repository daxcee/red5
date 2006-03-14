package org.red5.server.api;

/*
 * RED5 Open Source Flash Server - http://www.osflash.org/red5
 * 
 * Copyright � 2006 by respective authors (see below). All rights reserved.
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

/**
 * The Scope Handler controls actions performed against a scope object, and also
 * is notified of all events.
 * 
 * Gives fine grained control over what actions can be performed with the can*
 * methods. Allows for detailed reporting on what is happening within the scope
 * with the on* methods. This is the core interface users implement to create
 * applications.
 * 
 * The thread local connection is always available via the Red5 object within
 * these methods
 * 
 * @author The Red5 Project (red5@osflash.org)
 * @author Luke Hubbard (luke@codegent.com)
 */
public interface IScopeHandler {

	/**
	 * Called when a scope is created for the first time
	 * 
	 * @param scope
	 *            the new scope object
	 */
	void onCreateScope(IScope scope);

	/**
	 * Called just before a scope is disposed
	 */
	void onDisposeScope(IScope scope);

	/**
	 * Called just after a client has connected to a scope
	 * 
	 * @param conn
	 *            connection object
	 */
	void onConnect(IConnection conn);

	/**
	 * Called just before the client disconnects from the scope
	 * 
	 * @param conn
	 *            connection object
	 */
	void onDisconnect(IConnection conn);

	/**
	 * Called just before a service call This is a chance to modify the call
	 * object
	 * 
	 * @param call
	 *            the call object
	 * @return same or modified call object
	 */
	ICall preProcessServiceCall(ICall call);

	/**
	 * Called when a service is called
	 * 
	 * @param call
	 *            the call object
	 */
	void onServiceCall(ICall call);

	/**
	 * Called just after a service call This is a chance to modify the result
	 * object
	 * 
	 * @param call
	 * @return same or modified call object
	 */
	ICall postProcessServiceCall(ICall call);

	/**
	 * Called when an event is broadcast
	 * 
	 * @param event
	 *            the event object
	 */
	void onEventBroadcast(Object event);

	// The following methods will only be called for RTMP connections

	/**
	 * Called when the client begins publishing
	 * 
	 * @param stream
	 *            the stream object
	 */
	void onStreamPublishStart(IStream stream);

	/**
	 * Called when the client stops publishing
	 * 
	 * @param stream
	 *            the stream object
	 */
	void onStreamPublishStop(IStream stream);

	/**
	 * Can the client broadcast a stream
	 * 
	 * @param name
	 *            name of the stream
	 * @return true if the broadcast is allowed, otherwise false
	 */
	boolean canBroadcastStream(String name);

	/**
	 * Called when the broadcast starts
	 * 
	 * @param stream
	 *            the stream object
	 */
	void onBroadcastStreamStart(IStream stream);

	/**
	 * Called when a recording starts
	 * 
	 * @param stream
	 *            the stream object
	 */
	void onRecordStreamStart(IStream stream);

	/**
	 * Called when a recording stops
	 * 
	 * @param stream
	 *            the stream object
	 */
	void onRecordStreamStop(IStream stream);

	/**
	 * Called when a client subscribes to a broadcast
	 * 
	 * @param stream
	 *            the stream object
	 */
	void onBroadcastStreamSubscribe(IBroadcastStream stream);

	/**
	 * Called when a client unsubscribes from a broadcast
	 * 
	 * @param stream
	 *            the stream object
	 */
	void onBroadcastStreamUnsubscribe(IBroadcastStream stream);

	/**
	 * Called when a client connects to an on demand stream
	 * 
	 * @param stream
	 *            the stream object
	 */
	void onOnDemandStreamConnect(IOnDemandStream stream);

	/**
	 * Called when a client disconnects from an on demand stream
	 * 
	 * @param stream
	 *            the stream object
	 */
	void onOnDemandStreamDisconnect(IOnDemandStream stream);

	/**
	 * Called when a client connects to a shared object
	 * 
	 * @param so
	 *            the shared object
	 */
	void onSharedObjectConnect(ISharedObject so);

	/**
	 * Called when a shared object attribute is updated
	 * 
	 * @param so
	 *            the shared object
	 * @param key
	 *            the name of the attribute
	 * @param value
	 *            the value of the attribute
	 */
	void onSharedObjectUpdate(ISharedObject so, String key, Object value);

	/**
	 * Called when an attribute is deleted from the shared object
	 * 
	 * @param so
	 *            the shared object
	 * @param key
	 *            the name of the attribute to delete
	 */
	void onSharedObjectDelete(ISharedObject so, String key);

	/**
	 * Called when a shared object method call is sent
	 * 
	 * @param so
	 *            the shared object
	 * @param method
	 *            the method name to call
	 * @param params
	 *            the arguments
	 */
	void onSharedObjectSend(ISharedObject so, String method, Object[] params);

	/**
	 * Get the auth object for this scope handler
	 */
	IScopeAuth getScopeAuth(IScope scope); 
	
}