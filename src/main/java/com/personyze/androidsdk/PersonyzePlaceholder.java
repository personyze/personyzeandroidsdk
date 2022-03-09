package com.personyze.androidsdk;

import android.content.SharedPreferences;

import java.io.Serializable;

public class PersonyzePlaceholder implements Serializable
{	final protected int id;
	protected String name;
	String htmlId;
	int unitsCountMax;

	PersonyzePlaceholder(int id)
	{	this.id = id;
	}

	public boolean equals(Object other)
	{	return (other instanceof PersonyzePlaceholder) && id==((PersonyzePlaceholder)other).id;
	}

	boolean fromStorage(SharedPreferences storage)
	{	name = storage.getString("Placeholder Name "+id, null);
		htmlId = storage.getString("Placeholder HTML ID "+id, null);
		unitsCountMax = storage.getInt("Placeholder Units Count Max "+id, 0);
		return name != null;
	}

	void toStorage(SharedPreferences storage)
	{	SharedPreferences.Editor editor = storage.edit();
		editor.putString("Placeholder Name "+id, name);
		editor.putString("Placeholder HTML ID "+id, htmlId);
		editor.putInt("Placeholder Units Count Max "+id, unitsCountMax);
		editor.apply();
	}

	public int getId()
	{	return id;
	}

	public String getName()
	{	return name==null ? "" : name;
	}

	public String getHtmlId()
	{	return htmlId==null ? "" : htmlId;
	}

	public int getUnitsCountMax()
	{	return unitsCountMax;
	}
}
