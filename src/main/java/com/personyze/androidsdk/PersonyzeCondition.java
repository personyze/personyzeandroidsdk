package com.personyze.androidsdk;

import android.content.SharedPreferences;

import java.io.Serializable;

public class PersonyzeCondition implements Serializable
{	final protected int id;
	protected String name;

	PersonyzeCondition(int id)
	{	this.id = id;
	}

	public boolean equals(Object other)
	{	return (other instanceof PersonyzeCondition) && id==((PersonyzeCondition)other).id;
	}

	boolean fromStorage(SharedPreferences storage)
	{	name = storage.getString("Condition Name "+id, null);
		return name != null;
	}

	void toStorage(SharedPreferences storage)
	{	SharedPreferences.Editor editor = storage.edit();
		editor.putString("Condition Name "+id, name);
		editor.apply();
	}

	public int getId()
	{	return id;
	}

	public String getName()
	{	return name==null ? "" : name;
	}
}
