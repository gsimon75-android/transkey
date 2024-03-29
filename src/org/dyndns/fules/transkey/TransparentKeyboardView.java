package org.dyndns.fules.transkey;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader.TileMode;
import android.graphics.Typeface;
import android.inputmethodservice.KeyboardView.OnKeyboardActionListener;
import android.inputmethodservice.KeyboardView;
import android.media.MediaPlayer;
import android.net.Uri;
import android.text.InputType;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View.MeasureSpec;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Iterator;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

public class TransparentKeyboardView extends LinearLayout {
	// Constants
	private static final String		TAG = "TransparentKeyboard";

	// Internal params
	int					width, unit, defKeyWidth;
	float					fontSize, fontDispY;
	Paint					textPaint = new Paint();
	Paint					specPaint = new Paint();
	Paint					activePaint = new Paint();
	Paint					buttonPaint = new Paint();

	KeyboardView.OnKeyboardActionListener	actionListener;
	int					numModifiers = 0;
	HashMap<String, Integer>		modifiers = new HashMap();
	boolean					isTypingPassword;
	BitSet					mods = new BitSet(), locks = new BitSet();
	HashMap<String, KeyDefinition>		keys = new HashMap();

	LinearLayout.LayoutParams		lp;


	int
	seekImportant(XmlPullParser parser) throws XmlPullParserException, IOException {
		int eType;
		do {
			parser.next();
			eType = parser.getEventType();
		} while ((eType != XmlPullParser.END_DOCUMENT) && (eType != XmlPullParser.START_TAG) && (eType != XmlPullParser.END_TAG) && (eType != XmlPullParser.TEXT));
		/* event types (missing from the docs -> "Use the source, Luke, use the source!")
			public final static int START_DOCUMENT = 0;
			public final static int END_DOCUMENT = 1;
			public final static int START_TAG = 2;
			public final static int END_TAG = 3;
			public final static int TEXT = 4;
			public final static byte CDSECT = 5;
			public final static byte ENTITY_REF = 6;
			public static final byte IGNORABLE_WHITESPACE = 7;
			public static final byte PROCESSING_INSTRUCTION = 8;
			public static final int COMMENT = 9;
			public static final int DOCDECL = 10;
		*/
		return eType;
	}

	class Action {
		int		keyCode, layout, mod;
		String		code, text, cmd;
		boolean		isEmpty, isSpecial, isLock;
		BitSet		mods = new BitSet();
		Paint		paint;
		float		textWidth;

		public Action(XmlPullParser parser) throws XmlPullParserException, IOException {
			if ((parser.getEventType() != XmlPullParser.START_TAG) || !parser.getName().contentEquals("Action"))
				throw new XmlPullParserException("Expected <Action>", parser, null);

			isEmpty = isSpecial = isLock = false;
			keyCode = layout = mod = -1;

			int n = 0;
			for (int i = 0; i < parser.getAttributeCount(); i++) {
				String attrName = parser.getAttributeName(i);
				String attrValue = parser.getAttributeValue(i);

				if (attrName.contentEquals("key")) {
					keyCode = Integer.parseInt(attrValue);
					n++;
				}
				else if (attrName.contentEquals("code")) {
					code = java.net.URLDecoder.decode(attrValue);
					n++;
				}
				else if (attrName.contentEquals("layout")) {
					isSpecial = true;
					layout = Integer.parseInt(attrValue);
					n++;
				}
				else if (attrName.contentEquals("cmd")) {
					isSpecial = true;
					cmd = attrValue;
					n++;
				}
				else if (attrName.contentEquals("mod")) {
					isSpecial = true;
					mod = modifierId(attrValue);
					n++;
				}
				else if (attrName.contentEquals("isSpecial")) {
					isSpecial = isSpecial || Integer.parseInt(attrValue) != 0;
				}
				else if (attrName.contentEquals("lock")) {
					isLock = isLock || Integer.parseInt(attrValue) != 0;
				}
				else if (attrName.contentEquals("text")) {
					text = attrValue;
				}
				else {
					mods.set(modifierId(attrName));
				}
			}

			if (n != 1)
				throw new XmlPullParserException("Action: exactly one of key, code, cmd, mod or lock may be present", parser, null);

			if (text != null)
				text = java.net.URLDecoder.decode(text);
			else if (code != null)
				text = code;
			else if (cmd != null)
				text = cmd;
			else if (mod >= 0)
				text = "Oops"; // FIXME: think up some sensible label
			else if (keyCode >= 0)
				text = 'K'+String.valueOf(keyCode);
			else 
				text = 'L'+String.valueOf(layout);

			textWidth = textPaint.measureText(text);

			if ((seekImportant(parser) != XmlPullParser.END_TAG) || !parser.getName().contentEquals("Action"))
				throw new XmlPullParserException("Expected </Action>", parser, null);
		}

		Paint
		getPaint() {
			if (isLock && TransparentKeyboardView.this.locks.get(mod))
				return activePaint;
			if ((mod >= 0) && TransparentKeyboardView.this.mods.get(mod))
				return activePaint;
			if (isSpecial)
				return specPaint;
			return textPaint;
		}
	}

	class Row extends LinearLayout {
		class Key extends View {
			String			name;
			int			width;			// width of the key

			public Key(Context context, XmlPullParser parser) throws XmlPullParserException, IOException {
				super(context);
				String s;

				if ((parser.getEventType() != XmlPullParser.START_TAG) || !parser.getName().contentEquals("Key"))
					throw new XmlPullParserException("Expected <Key>", parser, null);

				name = parser.getAttributeValue(null, "name");
				s = parser.getAttributeValue(null, "width");
				width = (s != null) ? Integer.parseInt(s) : defKeyWidth;

				if ((seekImportant(parser) != XmlPullParser.END_TAG) || !parser.getName().contentEquals("Key"))
					throw new XmlPullParserException("Expected </Key>", parser, null);
			}

			@Override public boolean onTouchEvent(MotionEvent event) {
				switch (event.getAction()) {
					case MotionEvent.ACTION_UP:
						KeyDefinition km = keys.get(name);
						if (km == null)
							return true;
						Action ac = km.getAction(mods);
						if (ac == null)
							return true;
						return processAction(ac);

					case MotionEvent.ACTION_MOVE:
					case MotionEvent.ACTION_DOWN:
						return true;
				}
				return false;
			}

			@Override protected void onDraw(Canvas canvas) {
				int xmax = width * unit;
				int ymax = defKeyWidth * unit;
				//RectF buttonRect = new RectF(2, 2, xmax - 3, ymax - 3);
				//RectF buttonRect = new RectF(0, 0, xmax, ymax);
				RectF buttonRect = new RectF(1, 1, xmax - 1, ymax - 1);

				canvas.drawRoundRect(buttonRect, 5, 5, buttonPaint);

				KeyDefinition km = keys.get(name);
				if (km != null) {
					Action ac = km.getAction(mods);
					if (ac != null)
						canvas.drawText(ac.text, xmax / 2, (ymax - fontSize) / 2 + fontDispY, ac.getPaint());
				}
			}

			@Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
				// policy: if not specified by the parent as EXACTLY, use our own ideas
				int w = (View.MeasureSpec.getMode(widthMeasureSpec) == View.MeasureSpec.EXACTLY) ? View.MeasureSpec.getSize(widthMeasureSpec) : width * unit;
				int h = (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.EXACTLY) ? View.MeasureSpec.getSize(heightMeasureSpec) : defKeyWidth * unit;
				//Log.d(TAG, "Key.onMeasure; name='" + name + "', spec='(" + View.MeasureSpec.toString(widthMeasureSpec) + ", " + View.MeasureSpec.toString(heightMeasureSpec) + "), res='(" + String.valueOf(w) + ", " + String.valueOf(h) + ")'");
				setMeasuredDimension(w, h);
			}
		}

		class Align extends View {
			int width;

			public Align(Context context, XmlPullParser parser) throws XmlPullParserException, IOException {
				super(context);

				if ((parser.getEventType() != XmlPullParser.START_TAG) || !parser.getName().contentEquals("Align"))
					throw new XmlPullParserException("Expected <Align>", parser, null);

				String s = parser.getAttributeValue(null, "width");
				width = (s != null) ? Integer.parseInt(s) : defKeyWidth;

				if ((seekImportant(parser) != XmlPullParser.END_TAG) || !parser.getName().contentEquals("Align"))
					throw new XmlPullParserException("Expected </Align>", parser, null);
			}

			// Report the size of the alignment
			@Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
				int w = (View.MeasureSpec.getMode(widthMeasureSpec) == View.MeasureSpec.EXACTLY) ? View.MeasureSpec.getSize(widthMeasureSpec) : width * unit;
				int h = (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.EXACTLY) ? View.MeasureSpec.getSize(heightMeasureSpec) : defKeyWidth * unit;
				setMeasuredDimension(w, h);
			}
		}

		public Row(Context context, XmlPullParser parser) throws XmlPullParserException, IOException {
			super(context);

			setOrientation(android.widget.LinearLayout.HORIZONTAL);
			setGravity(android.view.Gravity.CENTER);
			//setBackgroundColor(0xff3f0000); // for debugging placement

			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
			//lp.setMargins(1, 0, 1, 0);

			buttonPaint = new Paint();
			buttonPaint.setAntiAlias(true);
			buttonPaint.setColor(Color.DKGRAY);

			while (seekImportant(parser) != XmlPullParser.END_TAG) {
				if (parser.getEventType() != XmlPullParser.START_TAG)
					throw new XmlPullParserException("Expected <Align> or <Key> tag", parser, null);

				if (parser.getName().contentEquals("Align"))
					addView(new Align(context, parser));
				else if (parser.getName().contentEquals("Key"))
					addView(new Key(context, parser));
				else
					throw new XmlPullParserException("Expected <Align> or <Key>", parser, null);
			}
			if (!parser.getName().contentEquals("Row"))
				throw new XmlPullParserException("Expected </Row>", parser, null);
		}

		/*public void commitState() {
			int n = getChildCount();
			for (int i = 0; i < n; i++) {
				View v = getChildAt(i);
				if (v instanceof Key)
					((Key)v).commitState();
			}
		}*/

		public void calculateSizes() {
			// Set the key background color
		}

		@Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
			// if the parent specified only a maximal width, make the row span the whole of it
			if (android.view.View.MeasureSpec.getMode(widthMeasureSpec) == android.view.View.MeasureSpec.AT_MOST) {
				widthMeasureSpec = android.view.View.MeasureSpec.makeMeasureSpec(
						android.view.View.MeasureSpec.EXACTLY,
						android.view.View.MeasureSpec.getSize(widthMeasureSpec));
			}
			super.onMeasure(widthMeasureSpec, heightMeasureSpec);
		}
	}

	class KeyDefinition {
		HashMap<BitSet, Action>	actions = new HashMap();
		String keyName;

		public KeyDefinition(XmlPullParser parser) throws XmlPullParserException, IOException {
			if ((parser.getEventType() != XmlPullParser.START_TAG) || !parser.getName().contentEquals("Key"))
				throw new XmlPullParserException("Expected <TransparentKeyboard>", parser, null);

			keyName = parser.getAttributeValue(null, "name");
			while (seekImportant(parser) != XmlPullParser.END_TAG) {
				if ((parser.getEventType() != XmlPullParser.START_TAG) || !parser.getName().contentEquals("Action"))
					throw new XmlPullParserException("Expected <Action> tag", parser, null);
				Action ac = new Action(parser);
				actions.put(ac.mods, ac);
				if (ac.mod >= 0) {
					BitSet b = new BitSet();
					b.set(ac.mod);
					actions.put(b, ac);
				}
			}

			if (!parser.getName().contentEquals("Key"))
				throw new XmlPullParserException("Expected </Key>", parser, null);
		}

		Action
		getAction(BitSet mods) {
			Action ac;
			
			ac = actions.get(mods);
			if (ac != null)
				return ac;

			ac = actions.get(new BitSet());
			if ((ac != null) && ac.isSpecial)
				return ac;

			return null;
		}
	}


	public TransparentKeyboardView(Context context) {
		super(context);
		//setBackgroundColor(0xff003f00); // for debugging placement
		setOrientation(android.widget.LinearLayout.VERTICAL);
		setGravity(android.view.Gravity.CENTER);
		lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		//lp.setMargins(0, 1, 0, 1);
		textPaint.setAntiAlias(true);
		textPaint.setColor(Color.WHITE);
		textPaint.setTextAlign(Paint.Align.CENTER);
		textPaint.setShadowLayer(3, 0, 2, 0xff000000);

		specPaint.setAntiAlias(true);
		specPaint.setColor(Color.CYAN);
		specPaint.setTextAlign(Paint.Align.CENTER);
		specPaint.setShadowLayer(3, 0, 2, 0xff000000);

		activePaint.setAntiAlias(true);
		activePaint.setColor(Color.YELLOW);
		activePaint.setTextAlign(Paint.Align.CENTER);
		activePaint.setShadowLayer(3, 0, 2, 0xff000000);
	}

	int modifierId(String s) {
		if (modifiers.containsKey(s))
			return modifiers.get(s).intValue();
		int n = numModifiers++;
		modifiers.put(s, new Integer(n));
		return n;
	}

	boolean
	checkModifier(String s) {
		return modifiers.containsKey(s) ? mods.get(modifiers.get(s).intValue()) : false;
	}

	public String readLayout(XmlPullParser parser) throws XmlPullParserException, IOException {
		String name, s;

		while (parser.getEventType() == XmlPullParser.START_DOCUMENT)
			parser.next();

		if ((parser.getEventType() != XmlPullParser.START_TAG) || !parser.getName().contentEquals("TransparentKeyboard"))
			throw new XmlPullParserException("Expected <TransparentKeyboard>", parser, null);

		name = parser.getAttributeValue(null, "name");
		if (name != null)
			Log.i(TAG, "Loading keyboard '"+name+"'");

		s = parser.getAttributeValue(null, "width");
		if (s == null)
			throw new XmlPullParserException("Expected 'width=N' attribute", parser, null);
		width = Integer.parseInt(s);

		s = parser.getAttributeValue(null, "default_key_width");
		if (s == null)
			throw new XmlPullParserException("Expected 'default_key_width=N' attribute", parser, null);
		defKeyWidth = Integer.parseInt(s);

		removeAllViews();
		while (seekImportant(parser) != XmlPullParser.END_TAG) {
			if (parser.getEventType() == XmlPullParser.START_TAG) {
				if (parser.getName().contentEquals("Row"))
					addView(new Row(getContext(), parser), lp);
				else if (parser.getName().contentEquals("Key")) {
					KeyDefinition km = new KeyDefinition(parser);
					keys.put(km.keyName, km);
				}
				else
					throw new XmlPullParserException("Expected <Row> or <Key>", parser, null);
			}
		}

		if (!parser.getName().contentEquals("TransparentKeyboard"))
			throw new XmlPullParserException("Expected </TransparentKeyboard>", parser, null);

		calculateSizesForMetrics(getResources().getDisplayMetrics());
		return name;
	}

	// Recalculate all the sizes according to the display metrics
	public void calculateSizesForMetrics(DisplayMetrics metrics) {
		// note: the metrics may change during the lifetime of the instance, so these precalculations could not be done in the constructor
		unit = metrics.widthPixels / width;
		textPaint.setTextSize(defKeyWidth * unit / 2);
		specPaint.setTextSize(defKeyWidth * unit / 2);
		activePaint.setTextSize(defKeyWidth * unit / 2);

		Paint.FontMetrics fm = textPaint.getFontMetrics();
		fontSize = fm.descent - fm.ascent;
		fontDispY = -fm.ascent;

		int ymax = defKeyWidth * unit;
		buttonPaint.setShader(new LinearGradient(0, 0, 0, ymax, 0xff696969, 0xff0a0a0a, android.graphics.Shader.TileMode.CLAMP));
	}

	public void setOnKeyboardActionListener(KeyboardView.OnKeyboardActionListener listener) {
		actionListener = listener;
	}

	public void setInputType(int type) {
		isTypingPassword = ((type & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT) &&
			((type & InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_PASSWORD);
	}

	private boolean processAction(Action cd) {
		if (cd == null)
			return false;

		if (cd.mod >= 0) {	// process a 'mod'
			mods.flip(cd.mod);
			if (cd.isLock)
				locks.flip(cd.mod); 
			invalidate();
		}
		else if (actionListener != null) {
			if (!mods.equals(locks)) {
				mods.clear();
				mods.or(locks);
				invalidate();
			}
			if (cd.code != null)
				actionListener.onText(cd.code); // process a 'code'
			else if (cd.keyCode >= 0)
				actionListener.onKey(cd.keyCode, null); // process a 'key'
			else if (actionListener instanceof TransparentKeyboard) {
				TransparentKeyboard transkey = (TransparentKeyboard)actionListener;
				if (cd.layout >= 0)
					transkey.updateLayout(cd.layout); // process a 'layout'
				else if ((cd.cmd != null) && (cd.cmd.length() > 0))
					transkey.execCmd(cd.cmd); // process a 'cmd'
			}
		}
		return true;
	}
}

// vim: set ai si sw=8 ts=8 noet:
