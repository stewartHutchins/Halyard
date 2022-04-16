package com.msd.gin.halyard.common;

import javax.annotation.Nullable;

import org.eclipse.rdf4j.model.Value;

public final class RDFObject extends RDFValue<Value> {
	static RDFObject create(RDFRole role, @Nullable Value obj, IdentifiableValueIO valueIO) {
		if(obj == null) {
			return null;
		}
		return new RDFObject(role, obj, valueIO);
	}

	/**
	 * Key hash size in bytes
	 */
	public static final int KEY_SIZE = 8;
	static final int END_KEY_SIZE = 2;
	static final byte[] STOP_KEY = HalyardTableUtils.STOP_KEY_64;
	static final byte[] END_STOP_KEY = HalyardTableUtils.STOP_KEY_16;

	private RDFObject(RDFRole role, Value val, IdentifiableValueIO valueIO) {
		super(role, val, valueIO);
	}
}
