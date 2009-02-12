package org.red5.server.so;

/*
 * RED5 Open Source Flash Server - http://www.osflash.org/red5
 * 
 * Copyright (c) 2006-2009 by respective authors (see below). All rights reserved.
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

import org.red5.server.api.event.IEventListener;

public class FlexSharedObjectMessage extends SharedObjectMessage {
	
	private static final long serialVersionUID = -6458750398936033347L;

	public FlexSharedObjectMessage() {}
    /**
     * Creates Flex2 Shared Object event with given name, version and persistence flag
     *
     * @param name          Event name
     * @param version       SO version
     * @param persistent    SO persistence flag
     */
    public FlexSharedObjectMessage(String name, int version, boolean persistent) {
		this(null, name, version, persistent);
	}

    /**
     * Creates Flex2 Shared Object event with given listener, name, SO version and persistence flag
     *
     * @param source         Event listener
     * @param name           Event name
     * @param version        SO version
     * @param persistent     SO persistence flag
     */
    public FlexSharedObjectMessage(IEventListener source, String name, int version,
			boolean persistent) {
    	super(source, name, version, persistent);
    }

	/** {@inheritDoc} */
    @Override
	public byte getDataType() {
		return TYPE_FLEX_SHARED_OBJECT;
	}

}