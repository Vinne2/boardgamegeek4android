package com.boardgamegeek.io;

import static org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
import static org.xmlpull.v1.XmlPullParser.END_TAG;
import static org.xmlpull.v1.XmlPullParser.START_TAG;

import java.io.IOException;

import org.xmlpull.v1.XmlPullParserException;

import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.provider.BaseColumns;
import android.util.Log;

import com.boardgamegeek.provider.BggContract.Buddies;
import com.boardgamegeek.util.StringUtils;

public class RemoteBuddiesHandler extends RemoteBggHandler {
	private static final String TAG = "RemoteBuddiesHandler";
	private int mUpdateCount = 0;
	private int mInsertCount = 0;

	public RemoteBuddiesHandler() {
		super();
	}

	@Override
	public int getCount() {
		return mUpdateCount + mInsertCount;
	}

	@Override
	protected String getRootNodeName() {
		return Tags.BUDDIES;
	}

	@Override
	protected void parseItems() throws XmlPullParserException, IOException {
		String[] projection = { BaseColumns._ID, };
		final int depth = mParser.getDepth();

		Uri uri;
		int type;
		while (((type = mParser.next()) != END_TAG || mParser.getDepth() > depth) && type != END_DOCUMENT) {
			if (type == START_TAG && Tags.BUDDY.equals(mParser.getName())) {

				int id = StringUtils.parseInt(mParser.getAttributeValue(null, Tags.ID));
				if (id > 0) {
					ContentValues values = new ContentValues();
					values.put(Buddies.UPDATED_LIST, System.currentTimeMillis());

					uri = Buddies.buildBuddyUri(id);
					Cursor cursor = null;
					try {
						cursor = mResolver.query(uri, projection, null, null, null);
						if (cursor.getCount() > 0) {
							mUpdateCount += mResolver.update(uri, values, null, null);
						} else {
							values.put(Buddies.BUDDY_ID, id);
							values.put(Buddies.BUDDY_NAME, mParser.getAttributeValue(null, Tags.NAME));
							mResolver.insert(Buddies.CONTENT_URI, values);
							mInsertCount++;
						}
					} finally {
						if (cursor != null && !cursor.isClosed()) {
							cursor.close();
						}
					}
				}
			}
		}
		Log.i(TAG, "Updated " + mUpdateCount + ", inserted " + mInsertCount + " buddies");
	}

	private interface Tags {
		String BUDDIES = "buddies";
		String BUDDY = "buddy";
		String ID = "id";
		String NAME = "name";
	}
}
