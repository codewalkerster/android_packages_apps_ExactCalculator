/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.calculator2;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.view.ActionMode;
import android.view.GestureDetector;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.OverScroller;
import android.widget.Toast;

// A text widget that is "infinitely" scrollable to the right,
// and obtains the text to display via a callback to Logic.
public class CalculatorResult extends AlignedTextView {
    static final int MAX_RIGHT_SCROLL = 10000000;
    static final int INVALID = MAX_RIGHT_SCROLL + 10000;
        // A larger value is unlikely to avoid running out of space
    final OverScroller mScroller;
    final GestureDetector mGestureDetector;
    class MyTouchListener implements View.OnTouchListener {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            return mGestureDetector.onTouchEvent(event);
        }
    }
    final MyTouchListener mTouchListener = new MyTouchListener();
    private Evaluator mEvaluator;
    private boolean mScrollable = false;
                            // A scrollable result is currently displayed.
    private boolean mValid = false;
                            // The result holds something valid; either a a number or an error
                            // message.
    private int mCurrentPos;// Position of right of display relative to decimal point, in pixels.
                            // Large positive values mean the decimal point is scrolled off the
                            // left of the display.  Zero means decimal point is barely displayed
                            // on the right.
    private int mLastPos;   // Position already reflected in display. Pixels.
    private int mMinPos;    // Minimum position before all digits disappear off the right. Pixels.
    private int mMaxPos;    // Maximum position before we start displaying the infinite
                            // sequence of trailing zeroes on the right. Pixels.
    private int mMaxCharPos;  // The same, but in characters.
    private int mLsd;       // Position of least-significant digit in result
                            // (1 = tenths, -1 = tens), or Integer.MAX_VALUE.
    private int mLastDisplayedDigit;  // Position of last digit actually displayed after adding
                                      // exponent.
    private final Object mWidthLock = new Object();
                            // Protects the next two fields.
    private int mWidthConstraint = -1;
                            // Our total width in pixels minus space for ellipsis.
    private float mCharWidth = 1;
                            // Maximum character width. For now we pretend that all characters
                            // have this width.
                            // TODO: We're not really using a fixed width font.  But it appears
                            // to be close enough for the characters we use that the difference
                            // is not noticeable.
    private static final int MAX_WIDTH = 100;
                            // Maximum number of digits displayed
    public static final int MAX_LEADING_ZEROES = 6;
                            // Maximum number of leading zeroes after decimal point before we
                            // switch to scientific notation with negative exponent.
    public static final int MAX_TRAILING_ZEROES = 6;
                            // Maximum number of trailing zeroes before the decimal point before
                            // we switch to scientific notation with positive exponent.
    private static final int SCI_NOTATION_EXTRA = 1;
                            // Extra digits for standard scientific notation.  In this case we
                            // have a deecimal point and no ellipsis.
    private ActionMode mActionMode;
    private final ForegroundColorSpan mExponentColorSpan;

    public CalculatorResult(Context context, AttributeSet attrs) {
        super(context, attrs);
        mScroller = new OverScroller(context);
        mGestureDetector = new GestureDetector(context,
            new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDown(MotionEvent e) {
                    return true;
                }
                @Override
                public boolean onFling(MotionEvent e1, MotionEvent e2,
                                       float velocityX, float velocityY) {
                    if (!mScroller.isFinished()) {
                        mCurrentPos = mScroller.getFinalX();
                    }
                    mScroller.forceFinished(true);
                    stopActionMode();
                    CalculatorResult.this.cancelLongPress();
                    // Ignore scrolls of error string, etc.
                    if (!mScrollable) return true;
                    mScroller.fling(mCurrentPos, 0, - (int) velocityX, 0  /* horizontal only */,
                                    mMinPos, mMaxPos, 0, 0);
                    postInvalidateOnAnimation();
                    return true;
                }
                @Override
                public boolean onScroll(MotionEvent e1, MotionEvent e2,
                                        float distanceX, float distanceY) {
                    int distance = (int)distanceX;
                    if (!mScroller.isFinished()) {
                        mCurrentPos = mScroller.getFinalX();
                    }
                    mScroller.forceFinished(true);
                    stopActionMode();
                    CalculatorResult.this.cancelLongPress();
                    if (!mScrollable) return true;
                    if (mCurrentPos + distance < mMinPos) {
                        distance = mMinPos - mCurrentPos;
                    } else if (mCurrentPos + distance > mMaxPos) {
                        distance = mMaxPos - mCurrentPos;
                    }
                    int duration = (int)(e2.getEventTime() - e1.getEventTime());
                    if (duration < 1 || duration > 100) duration = 10;
                    mScroller.startScroll(mCurrentPos, 0, distance, 0, (int)duration);
                    postInvalidateOnAnimation();
                    return true;
                }
                @Override
                public void onLongPress(MotionEvent e) {
                    if (mValid) {
                        mActionMode = startActionMode(mCopyActionModeCallback,
                                ActionMode.TYPE_FLOATING);
                    }
                }
            });
        setOnTouchListener(mTouchListener);
        setHorizontallyScrolling(false);  // do it ourselves
        setCursorVisible(false);
        mExponentColorSpan = new ForegroundColorSpan(
                context.getColor(R.color.display_result_exponent_text_color));

        // Copy ActionMode is triggered explicitly, not through
        // setCustomSelectionActionModeCallback.
    }

    void setEvaluator(Evaluator evaluator) {
        mEvaluator = evaluator;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        final TextPaint paint = getPaint();
        final int newWidthConstraint = MeasureSpec.getSize(widthMeasureSpec)
                - (getPaddingLeft() + getPaddingRight())
                - (int) Math.ceil(Layout.getDesiredWidth(KeyMaps.ELLIPSIS, paint));
        final float newCharWidth = Layout.getDesiredWidth("\u2007", paint);
        synchronized(mWidthLock) {
            mWidthConstraint = newWidthConstraint;
            mCharWidth = newCharWidth;
        }
    }

    // Return the length of the exponent representation for the given exponent, in
    // characters.
    private final int expLen(int exp) {
        if (exp == 0) return 0;
        return (int)Math.ceil(Math.log10(Math.abs((double)exp))) + (exp >= 0 ? 1 : 2);
    }

    /**
     * Initiate display of a new result.
     * The parameters specify various properties of the result.
     * @param initPrec Initial display precision computed by evaluator. (1 = tenths digit)
     * @param msd Position of most significant digit.  Offset from left of string.
                  Evaluator.INVALID_MSD if unknown.
     * @param leastDigPos Position of least significant digit (1 = tenths digit)
     *                    or Integer.MAX_VALUE.
     * @param truncatedWholePart Result up to but not including decimal point.
                                 Currently we only use the length.
     */
    void displayResult(int initPrec, int msd, int leastDigPos, String truncatedWholePart) {
        initPositions(initPrec, msd, leastDigPos, truncatedWholePart);
        redisplay();
    }

    /**
     * Set up scroll bounds and determine whether the result is scrollable, based on the
     * supplied information about the result.
     * This is unfortunately complicated because we need to predict whether trailing digits
     * will eventually be replaced by an exponent.
     * Just appending the exponent during formatting would be simpler, but would produce
     * jumpier results during transitions.
     */
    private void initPositions(int initPrec, int msd, int leastDigPos, String truncatedWholePart) {
        float charWidth;
        int maxChars = getMaxChars();
        mLastPos = INVALID;
        mLsd = leastDigPos;
        synchronized(mWidthLock) {
            charWidth = mCharWidth;
        }
        mCurrentPos = mMinPos = (int) Math.round(initPrec * charWidth);
        // Prevent scrolling past initial position, which is calculated to show leading digits.
        if (msd == Evaluator.INVALID_MSD) {
            // Possible zero value
            if (leastDigPos == Integer.MIN_VALUE) {
                // Definite zero value.
                mMaxPos = mMinPos;
                mMaxCharPos = (int) Math.round(mMaxPos/charWidth);
                mScrollable = false;
            } else {
                // May be very small nonzero value.  Allow user to find out.
                mMaxPos = mMaxCharPos = MAX_RIGHT_SCROLL;
                mScrollable = true;
            }
            return;
        }
        int wholeLen =  truncatedWholePart.length();
        int negative = truncatedWholePart.charAt(0) == '-' ? 1 : 0;
        boolean adjustedForExp = false;  // Adjusted for normal exponent.
        if (msd > wholeLen && msd <= wholeLen + 3) {
            // Avoid tiny negative exponent; pretend msd is just to the right of decimal point.
            msd = wholeLen - 1;
        }
        int minCharPos = msd - negative - wholeLen;
                                // Position of leftmost significant digit relative to dec. point.
                                // Usually negative.
        mMaxCharPos = MAX_RIGHT_SCROLL; // How far does it make sense to scroll right?
        // If msd is left of decimal point should logically be
        // mMinPos = - (int) Math.ceil(getPaint().measureText(truncatedWholePart)), but
        // we eventually translate to a character position by dividing by mCharWidth.
        // To avoid rounding issues, we use the analogous computation here.
        if (minCharPos > -1 && minCharPos < MAX_LEADING_ZEROES + 2) {
            // Small number of leading zeroes, avoid scientific notation.
            minCharPos = -1;
        }
        if (leastDigPos < MAX_RIGHT_SCROLL) {
            mMaxCharPos = leastDigPos;
            if (mMaxCharPos < -1 && mMaxCharPos > -(MAX_TRAILING_ZEROES + 2)) {
                mMaxCharPos = -1;
            }
            // leastDigPos is positive or negative, never 0.
            if (mMaxCharPos < -1) {
                // Number entirely to left of decimal point.
                // We'll need a positive exponent or displayed zeros to display entire number.
                mMaxCharPos = Math.min(-1, mMaxCharPos + expLen(-minCharPos - 1));
                if (mMaxCharPos >= -1) {
                    // Unlikely; huge exponent.
                    mMaxCharPos = -1;
                } else {
                    adjustedForExp = true;
                }
            } else if (minCharPos > -1 || mMaxCharPos >= maxChars) {
                // Number either entirely to the right of decimal point, or decimal point not
                // visible when scrolled to the right.
                // We will need an exponent when looking at the rightmost digit.
                // Allow additional scrolling to make room.
                mMaxCharPos += expLen(-(minCharPos + 1));
                adjustedForExp = true;
                // Assumed an exponent for standard scientific notation for now.
                // Adjusted below if necessary.
            }
            mScrollable = (mMaxCharPos - minCharPos + negative >= maxChars);
            if (mScrollable) {
                if (adjustedForExp) {
                    // We may need a slightly larger negative exponent while scrolling.
                    mMaxCharPos += expLen(-leastDigPos) - expLen(-(minCharPos + 1));
                }
            }
            mMaxPos = Math.min((int) Math.round(mMaxCharPos * charWidth), MAX_RIGHT_SCROLL);
            if (!mScrollable) {
                // Position the number consistently with our assumptions to make sure it
                // actually fits.
                mCurrentPos = mMaxPos;
            }
        } else {
            mMaxPos = mMaxCharPos = MAX_RIGHT_SCROLL;
            mScrollable = true;
        }
    }

    void displayError(int resourceId) {
        mValid = true;
        mScrollable = false;
        setText(resourceId);
    }

    private final int MAX_COPY_SIZE = 1000000;

    /*
     * Return the most significant digit position in the given string or Evaluator.INVALID_MSD.
     * Unlike Evaluator.getMsdPos, we treat a final 1 as significant.
     */
    public static int getNaiveMsdPos(String s) {
        int len = s.length();
        int nonzeroPos = -1;
        for (int i = 0; i < len; ++i) {
            char c = s.charAt(i);
            if (c != '-' && c != '.' && c != '0') {
                return i;
            }
        }
        return Evaluator.INVALID_MSD;
    }

    // Format a result returned by Evaluator.getString() into a single line containing ellipses
    // (if appropriate) and an exponent (if appropriate).  prec is the value that was passed to
    // getString and thus identifies the significance of the rightmost digit.
    // A value of 1 means the rightmost digits corresponds to tenths.
    // maxDigs is the maximum number of characters in the result.
    // We set lastDisplayedDigit[0] to the position of the last digit actually appearing in
    // the display.
    // If forcePrecision is true, we make sure that the last displayed digit corresponds to
    // prec, and allow maxDigs to be exceeded in assing the exponent.
    // We add two distinct kinds of exponents:
    // 1) If the final result contains the leading digit we use standard scientific notation.
    // 2) If not, we add an exponent corresponding to an interpretation of the final result as
    //    an integer.
    // We add an ellipsis on the left if the result was truncated.
    // We add ellipses and exponents in a way that leaves most digits in the position they
    // would have been in had we not done so.
    // This minimizes jumps as a result of scrolling.  Result is NOT internationalized,
    // uses "e" for exponent.
    public String formatResult(String res, int prec, int maxDigs, boolean truncated,
            boolean negative, int lastDisplayedDigit[], boolean forcePrecision) {
        int msd;  // Position of most significant digit in res or indication its outside res.
        int minusSpace = negative ? 1 : 0;
        if (truncated) {
            res = KeyMaps.ELLIPSIS + res.substring(1, res.length());
            msd = -1;
        } else {
            msd = getNaiveMsdPos(res);  // INVALID_MSD is OK and is treated as large.
        }
        int decIndex = res.indexOf('.');
        int resLen = res.length();
        lastDisplayedDigit[0] = prec;
        if ((decIndex == -1 || msd != Evaluator.INVALID_MSD
                && msd - decIndex > MAX_LEADING_ZEROES + 1) &&  prec != -1) {
            // No decimal point displayed, and it's not just to the right of the last digit,
            // or we should suppress leading zeroes.
            // Add an exponent to let the user track which digits are currently displayed.
            // This is a bit tricky, since the number of displayed digits affects the displayed
            // exponent, which can affect the room we have for mantissa digits.  We occasionally
            // display one digit too few. This is sometimes unavoidable, but we could
            // avoid it in more cases.
            int exp = prec > 0 ? -prec : -prec - 1;
                    // Can be used as TYPE (2) EXPONENT. -1 accounts for decimal point.
            boolean hasPoint = false;
            if (msd < maxDigs - 1 && msd >= 0 &&
                resLen - msd + 1 /* dec. pt. */ + minusSpace <= maxDigs + SCI_NOTATION_EXTRA) {
                // TYPE (1) EXPONENT computation and transformation:
                // Leading digit is in display window. Use standard calculator scientific notation
                // with one digit to the left of the decimal point. Insert decimal point and
                // delete leading zeroes.
                // We try to keep leading digits roughly in position, and never
                // lengthen the result by more than SCI_NOTATION_EXTRA.
                String fraction = res.substring(msd + 1, resLen);
                res = (negative ? "-" : "") + res.substring(msd, msd + 1) + "." + fraction;
                exp += resLen - msd - 1;
                // Original exp was correct for decimal point at right of fraction.
                // Adjust by length of fraction.
                resLen = res.length();
                hasPoint = true;
            }
            if (exp != 0 || truncated) {
                // Actually add the exponent of either type:
                String expAsString = Integer.toString(exp);
                int expDigits = expAsString.length();
                if (!forcePrecision) {
                    int dropDigits = expDigits + 1;
                        // Drop digits even if there is room. Otherwise the scrolling gets jumpy.
                    if (dropDigits >= resLen - 1) {
                        dropDigits = Math.max(resLen - 2, 0);
                        // Jumpy is better than no mantissa.  Probably impossible anyway.
                    }
                    if (!hasPoint) {
                        // Special handling for TYPE(2) EXPONENT:
                        exp += dropDigits;
                        expAsString = Integer.toString(exp);
                        // Adjust for digits we are about to drop to drop to make room for exponent.
                        // This can affect the room we have for the mantissa. We adjust only for
                        // positive exponents, when it could otherwise result in a truncated
                        // displayed result.
                        if (exp > 0 && expAsString.length() > expDigits) {
                            // ++expDigits; (dead code)
                            ++dropDigits;
                            ++exp;
                            expAsString = Integer.toString(exp);
                            // This cannot increase the length a second time.
                        }
                        if (prec - dropDigits > mLsd) {
                            // This can happen if e.g. result = 10^40 + 10^10
                            // It turns out we would otherwise display ...10e9 because it takes
                            // the same amount of space as ...1e10 but shows one more digit.
                            // But we don't want to display a trailing zero, even if it's free.
                            ++dropDigits;
                            ++exp;
                            expAsString = Integer.toString(exp);
                        }
                    }
                    res = res.substring(0, resLen - dropDigits);
                    lastDisplayedDigit[0] -= dropDigits;
                }
                res = res + "e" + expAsString;
            } // else don't add zero exponent
        }
        return res;
    }

    /**
     * Get formatted, but not internationalized, result from mEvaluator.
     * @param pos requested position (1 = tenths) of last included digit.
     * @param maxSize Maximum number of characters (more or less) in result.
     * @param lastDisplayedPrec Zeroth entry is set to actual position of last included digit,
     *                          after adjusting for exponent, etc.
     * @param forcePrecision Ensure that last included digit is at pos, at the expense
     *                       of treating maxSize as a soft limit.
     */
    private String getFormattedResult(int pos, int maxSize, int lastDisplayedDigit[],
            boolean forcePrecision) {
        final boolean truncated[] = new boolean[1];
        final boolean negative[] = new boolean[1];
        final int requested_prec[] = {pos};
        final String raw_res = mEvaluator.getString(requested_prec, mMaxCharPos,
                maxSize, truncated, negative);
        return formatResult(raw_res, requested_prec[0], maxSize, truncated[0], negative[0],
                lastDisplayedDigit, forcePrecision);
   }

    // Return entire result (within reason) up to current displayed precision.
    public String getFullText() {
        if (!mValid) return "";
        if (!mScrollable) return getText().toString();
        int currentCharPos = getCurrentCharPos();
        int unused[] = new int[1];
        return KeyMaps.translateResult(getFormattedResult(mLastDisplayedDigit, MAX_COPY_SIZE,
                unused, true));
    }

    public boolean fullTextIsExact() {
        return !mScrollable
                || mMaxCharPos == getCurrentCharPos() && mMaxCharPos != MAX_RIGHT_SCROLL;
    }

    /**
     * Return the maximum number of characters that will fit in the result display.
     * May be called asynchronously from non-UI thread.
     */
    int getMaxChars() {
        int result;
        synchronized(mWidthLock) {
            result = (int) Math.floor(mWidthConstraint / mCharWidth);
            // We can apparently finish evaluating before onMeasure in CalculatorText has been
            // called, in which case we get 0 or -1 as the width constraint.
        }
        if (result <= 0) {
            // Return something conservatively big, to force sufficient evaluation.
            return MAX_WIDTH;
        } else {
            // Always allow for the ellipsis character which already accounted for in the width
            // constraint.
            return result + 1;
        }
    }

    /**
     * @return {@code true} if the currently displayed result is scrollable
     */
    public boolean isScrollable() {
        return mScrollable;
    }

    int getCurrentCharPos() {
        synchronized(mWidthLock) {
            return (int) Math.round(mCurrentPos / mCharWidth);
        }
    }

    void clear() {
        mValid = false;
        mScrollable = false;
        setText("");
    }

    void redisplay() {
        int currentCharPos = getCurrentCharPos();
        int maxChars = getMaxChars();
        int lastDisplayedDigit[] = new int[1];
        String result = getFormattedResult(currentCharPos, maxChars, lastDisplayedDigit, false);
        int epos = result.indexOf('e');
        result = KeyMaps.translateResult(result);
        if (epos > 0 && result.indexOf('.') == -1) {
          // Gray out exponent if used as position indicator
            SpannableString formattedResult = new SpannableString(result);
            formattedResult.setSpan(mExponentColorSpan, epos, result.length(),
                                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            setText(formattedResult);
        } else {
            setText(result);
        }
        mLastDisplayedDigit = lastDisplayedDigit[0];
        mValid = true;
    }

    @Override
    public void computeScroll() {
        if (!mScrollable) return;
        if (mScroller.computeScrollOffset()) {
            mCurrentPos = mScroller.getCurrX();
            if (mCurrentPos != mLastPos) {
                mLastPos = mCurrentPos;
                redisplay();
            }
            if (!mScroller.isFinished()) {
                postInvalidateOnAnimation();
            }
        }
    }

    // Copy support:

    private ActionMode.Callback mCopyActionModeCallback = new ActionMode.Callback() {
        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            MenuInflater inflater = mode.getMenuInflater();
            inflater.inflate(R.menu.copy, menu);
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return false; // Return false if nothing is done
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            switch (item.getItemId()) {
            case R.id.menu_copy:
                copyContent();
                mode.finish();
                return true;
            default:
                return false;
            }
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            mActionMode = null;
        }
    };

    public boolean stopActionMode() {
        if (mActionMode != null) {
            mActionMode.finish();
            return true;
        }
        return false;
    }

    private void setPrimaryClip(ClipData clip) {
        ClipboardManager clipboard = (ClipboardManager) getContext().
                                               getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(clip);
    }

    private void copyContent() {
        final CharSequence text = getFullText();
        ClipboardManager clipboard =
                (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        // We include a tag URI, to allow us to recognize our own results and handle them
        // specially.
        ClipData.Item newItem = new ClipData.Item(text, null, mEvaluator.capture());
        String[] mimeTypes = new String[] {ClipDescription.MIMETYPE_TEXT_PLAIN};
        ClipData cd = new ClipData("calculator result", mimeTypes, newItem);
        clipboard.setPrimaryClip(cd);
        Toast.makeText(getContext(), R.string.text_copied_toast, Toast.LENGTH_SHORT).show();
    }

}
