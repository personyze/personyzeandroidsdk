package com.personyze.androidsdk;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class PersonyzeResult
{	public ArrayList<PersonyzeCondition> conditions;
	public ArrayList<PersonyzeAction> actions;

	boolean fromStorage(SharedPreferences storage, Context context)
	{	Set<String> conditionsSet = storage.getStringSet("Conditions", null);
		Set<String> actionsSet = storage.getStringSet("Actions", null);
		conditions = null;
		actions = null;
		if (conditionsSet!=null && actionsSet!=null)
		{	conditions = new ArrayList<>(conditionsSet.size());
			actions = new ArrayList<>(actionsSet.size());
			// conditions
			for (String s : conditionsSet)
			{	PersonyzeCondition condition = new PersonyzeCondition(PersonyzeTracker.intVal(s));
				if (!condition.fromStorage(storage))
				{	conditions = null;
					return false;
				}
				conditions.add(condition);
			}
			// actions
			for (String s : actionsSet)
			{	PersonyzeAction action = new PersonyzeAction(PersonyzeTracker.intVal(s));
				if (!action.fromStorage(storage))
				{	conditions = null;
					actions = null;
					return false;
				}
				try
				{	action.dataFromStorage(context);
				}
				catch (Exception e)
				{	Log.e("Personyze", e.getLocalizedMessage());
				}
				actions.add(action);
			}
			// ok
			return true;
		}
		return false;
	}

	void toStorage(SharedPreferences storage)
	{	if (conditions!=null && actions!=null)
		{	SharedPreferences.Editor editor = storage.edit();
			// conditions
			Set<String> set = new HashSet<>();
			for (PersonyzeCondition condition : conditions)
			{	set.add(Integer.toString(condition.id));
			}
			editor.putStringSet("Conditions", set);
			// actions
			set.clear();
			for (PersonyzeAction action : actions)
			{	set.add(Integer.toString(action.id));
			}
			editor.putStringSet("Actions", set);
			// ok
			editor.apply();
		}
	}
}
