package net.kdt.pojavlaunch.customcontrols.mouse;

import static android.view.MotionEvent.ACTION_CANCEL;
import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_MOVE;
import static android.view.MotionEvent.ACTION_POINTER_DOWN;
import static android.view.MotionEvent.ACTION_POINTER_UP;
import static android.view.MotionEvent.ACTION_UP;

import android.view.MotionEvent;

import net.kdt.pojavlaunch.Tools;

/**
 * Class aiming at better detecting X-tap events regardless of the POINTERS
 * Only uses the least amount of events possible,
 * since we aren't guaranteed to have all events in order
 */
public class TapDetector {

    public final static int DETECTION_METHOD_DOWN = 0x1;
    public final static int DETECTION_METHOD_UP = 0x2;
    public final static int DETECTION_METHOD_BOTH = 0x3; //Unused for now

    private final static int TAP_MIN_DELTA_MS = -1;
    private final static int TAP_MAX_DELTA_MS = 300;
    private static final int TAP_SLOP_PX = Math.round(Tools.dpToPx(8));
    private static final int TAP_SLOP_SQUARE_PX = TAP_SLOP_PX * TAP_SLOP_PX;

    private final int mTapNumberToDetect;
    private int mCurrentTapNumber = 0;

    private final int mDetectionMethod;

    private long mLastEventTime = 0;
    private float mLastX = 9999;
    private float mLastY = 9999;
    private int mTrackedPointerId = -1;
    private boolean mTapMovedBeyondSlop;
    private boolean mIgnoreUntilAllPointersUp;

    /**
     * @param tapNumberToDetect How many taps are needed before onTouchEvent returns True.
     * @param detectionMethod Method used to detect touches. See DETECTION_METHOD constants above.
     */
    public TapDetector(int tapNumberToDetect, int detectionMethod){
        this.mDetectionMethod = detectionMethod;
        //We expect both ACTION_DOWN and ACTION_UP for the DETECTION_METHOD_BOTH
        this.mTapNumberToDetect = detectBothTouch() ? 2*tapNumberToDetect : tapNumberToDetect;
    }

    /**
     * A function to call when you have a touch event.
     * @param e The MotionEvent to inspect
     * @return whether or not a X-tap happened for a pointer
     */
    public boolean onTouchEvent(MotionEvent e){
        int eventAction = e.getActionMasked();
        if (eventAction == ACTION_CANCEL) {
            resetTapDetectionState();
            mIgnoreUntilAllPointersUp = false;
            return false;
        }
        if (detectBothTouch() && mIgnoreUntilAllPointersUp) {
            if (eventAction == ACTION_UP || eventAction == ACTION_CANCEL) {
                mIgnoreUntilAllPointersUp = false;
                resetTapDetectionState();
            }
            return false;
        }
        if (detectBothTouch() && mCurrentTapNumber > 0 && eventAction == ACTION_POINTER_DOWN) {
            // A multi-finger gesture must not complete the down/up tap as a click.
            resetTapDetectionState();
            mIgnoreUntilAllPointersUp = true;
            return false;
        }
        if (detectBothTouch() && mCurrentTapNumber > 0 && eventAction == ACTION_MOVE) {
            int trackedIndex = e.findPointerIndex(mTrackedPointerId);
            if (trackedIndex < 0) {
                mTapMovedBeyondSlop = true;
            } else {
                float dx = e.getX(trackedIndex) - mLastX;
                float dy = e.getY(trackedIndex) - mLastY;
                if (dx * dx + dy * dy > TAP_SLOP_SQUARE_PX) mTapMovedBeyondSlop = true;
            }
            return false;
        }

        int pointerIndex = -1;

        //Get the event to look forward
        if(detectDownTouch()){
            if(eventAction == ACTION_DOWN) pointerIndex = 0;
            else if(eventAction == ACTION_POINTER_DOWN) pointerIndex = e.getActionIndex();
        }
        if(detectUpTouch()){
            if(eventAction == ACTION_UP) pointerIndex = 0;
            else if(eventAction == ACTION_POINTER_UP) pointerIndex = e.getActionIndex();
        }

        if(pointerIndex == -1) return false; // Useless event
        if (detectBothTouch() && (eventAction == ACTION_DOWN || eventAction == ACTION_POINTER_DOWN)) {
            mTrackedPointerId = e.getPointerId(pointerIndex);
            mTapMovedBeyondSlop = false;
        }

        //Store current event info
        float eventX = e.getX(pointerIndex);
        float eventY = e.getY(pointerIndex);
        long eventTime = e.getEventTime();

        //Compute deltas
        long deltaTime = eventTime - mLastEventTime;
        int deltaX = (int) mLastX - (int) eventX;
        int deltaY = (int) mLastY - (int) eventY;

        //Store current event info to persist on next event
        mLastEventTime = eventTime;
        mLastX = eventX;
        mLastY = eventY;

        //Check for high enough speed and precision
        if(mCurrentTapNumber > 0){
            // For DETECTION_METHOD_BOTH, the DOWN and UP being checked here are the two
            // halves of ONE continuous touch, not two separate taps -- there is no
            // legitimate reason to invalidate a tap just because the finger was held a
            // bit longer before lifting, only because it moved too far (drag/scroll).
            // A time limit only makes sense for genuine multi-tap counting between
            // SEPARATE taps (DETECTION_METHOD_DOWN/UP, e.g. HotbarView's double-tap),
            // where it's kept as-is.
            boolean tooSlow = !detectBothTouch() && (deltaTime < TAP_MIN_DELTA_MS || deltaTime > TAP_MAX_DELTA_MS);
            boolean movedTooFar = mTapMovedBeyondSlop || (deltaX*deltaX + deltaY*deltaY) > TAP_SLOP_SQUARE_PX;
            if (tooSlow || movedTooFar) {
                if (mDetectionMethod == DETECTION_METHOD_BOTH && (eventAction == ACTION_UP || eventAction == ACTION_POINTER_UP)) {
                    // For the both method, the user is expected to start with a down action.
                    resetTapDetectionState();
                    return false;
                } else {
                    // We invalidate previous taps, not this one though
                    mCurrentTapNumber = 0;
                }
            }
        }

        //A worthy tap happened
        mCurrentTapNumber += 1;
        if(mCurrentTapNumber >= mTapNumberToDetect){
           resetTapDetectionState();
           return true;
        }

        //If not enough taps are reached
        return false;
    }

    /**
     * Reset the double tap values.
     */
   private void resetTapDetectionState(){
       mCurrentTapNumber = 0;
       mLastEventTime = 0;
       mLastX = 9999;
       mLastY = 9999;
       mTrackedPointerId = -1;
       mTapMovedBeyondSlop = false;
   }

   private boolean detectDownTouch(){
       return (mDetectionMethod & DETECTION_METHOD_DOWN) == DETECTION_METHOD_DOWN;
   }

   private boolean detectUpTouch(){
       return (mDetectionMethod & DETECTION_METHOD_UP) == DETECTION_METHOD_UP;
   }

   private boolean detectBothTouch(){
       return mDetectionMethod == DETECTION_METHOD_BOTH;
   }
}
