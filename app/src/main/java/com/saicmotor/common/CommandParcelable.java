package com.saicmotor.common;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * SAIC's inter-app command envelope, re-declared here byte-for-byte.
 *
 * <p>
 * <b>The package and class name are load-bearing and must not be changed.</b>
 * {@code Intent.putExtra(String, Parcelable)} writes the value's
 * {@code getClass().getName()} into the parcel, and the receiving process looks
 * that name up in <em>its own</em> classloader. So a command only unmarshals in
 * the stock apps if we hand them a class literally called
 * {@code com.saicmotor.common.CommandParcelable} whose {@code writeToParcel}
 * emits the same fields in the same order. Renaming the class, moving it to
 * another package, or reordering the writes all produce a
 * {@code BadParcelableException} on the far side.
 *
 * <p>
 * Field order and types were read out of the R33 SWI69 1100 firmware's own copy,
 * which is compiled into {@code Launcher_eh32_ll.apk}, {@code Media_eh32_ll.apk}
 * and {@code Radio_eh32_ll.apk} alike:
 *
 * <pre>
 *   writeInt(mEvent); writeString(mPackage);
 *   writeInt(mTargetType); writeInt(mTargetState);
 *   writeBundle(mCommandParam);
 * </pre>
 *
 * <p>
 * Only the setters this launcher needs are kept; the stock class also has
 * getters and several {@code sendCommand} overloads. See
 * {@link com.custom.launcher.saic.SaicSourceSwitch} for how it is delivered.
 */
public class CommandParcelable implements Parcelable {

    /** Intent extra key the stock services read the command out of. */
    public static final String COMMAND_KEY = "command";

    private int mEvent = -1;
    private String mPackage = "";
    private int mTargetType = -1;
    private int mTargetState = -1;
    private Bundle mCommandParam = new Bundle();

    public CommandParcelable() {
    }

    protected CommandParcelable(Parcel parcel) {
        mEvent = parcel.readInt();
        mPackage = parcel.readString();
        mTargetType = parcel.readInt();
        mTargetState = parcel.readInt();
        mCommandParam = parcel.readBundle(Bundle.class.getClassLoader());
    }

    public static final Creator<CommandParcelable> CREATOR = new Creator<CommandParcelable>() {
        @Override
        public CommandParcelable createFromParcel(Parcel parcel) {
            return new CommandParcelable(parcel);
        }

        @Override
        public CommandParcelable[] newArray(int size) {
            return new CommandParcelable[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(mEvent);
        parcel.writeString(mPackage);
        parcel.writeInt(mTargetType);
        parcel.writeInt(mTargetState);
        parcel.writeBundle(mCommandParam);
    }

    public void setEvent(int event) {
        mEvent = event;
    }

    /**
     * Who the command is <em>from</em>, not who it is for — the stock launcher
     * always puts its own package here and addresses the recipient with the
     * intent's component.
     */
    public void setTargetPackage(String packageName) {
        mPackage = packageName;
    }

    public void setTargetType(int targetType) {
        mTargetType = targetType;
    }

    public void setTargetState(int targetState) {
        mTargetState = targetState;
    }

    public int getEvent() {
        return mEvent;
    }

    public String getTargetPackage() {
        return mPackage;
    }

    public int getTargetType() {
        return mTargetType;
    }

    public int getTargetState() {
        return mTargetState;
    }

    @Override
    public String toString() {
        return "CommandParcelable{event=" + mEvent + ", package='" + mPackage
                + "', targetType=" + mTargetType + ", targetState=" + mTargetState + '}';
    }
}
