package com.github.stkent.callingcard;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.support.annotation.ColorInt;
import android.support.annotation.DrawableRes;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.squareup.picasso.Picasso;

import butterknife.Bind;
import butterknife.ButterKnife;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static com.github.stkent.callingcard.UserView.BorderColor.GREEN;
import static com.github.stkent.callingcard.UserView.BorderColor.GREY;
import static com.github.stkent.callingcard.UserView.BorderColor.RED;

public final class UserView extends LinearLayout {

    enum BorderColor {
        GREY, GREEN, RED
    }

    private static final String TAG = "UserView";

    @DrawableRes
    private static final int PLACEHOLDER_IMAGE_RES = R.drawable.img_placeholder;

    @Bind(R.id.name_field)
    protected TextView nameField;

    @Bind(R.id.email_address_field)
    protected TextView emailAddressField;

    @Bind(R.id.photo_image_view)
    protected ImageView photoImageView;

    public UserView(final Context context) {
        this(context, null);
    }

    public UserView(final Context context, @Nullable final AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public UserView(
            final Context context,
            @Nullable final AttributeSet attrs,
            final int defStyleAttr) {

        super(context, attrs, defStyleAttr);
        setLayoutParams(new ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        setOrientation(VERTICAL);
        setBorderColor(GREY);

        final int padding = getResources().getDimensionPixelSize(R.dimen.user_view_padding);
        setPadding(padding, padding, padding, padding);

        LayoutInflater.from(context).inflate(R.layout.include_user_view, this);
        ButterKnife.bind(this);
    }

    public void bindUser(@NonNull final User user) {
        nameField.setText(user.getName());
        emailAddressField.setText(user.getEmailAddress());

        final Uri photoUrl = user.getPhotoUrl();

        if (photoUrl != null) {
            Log.d(TAG, "bindUser: User photo URL found: " + photoUrl);
            Log.d(TAG, "bindUser: Loading photo...");

            Picasso.with(getContext())
                    .load(photoUrl)
                    .placeholder(PLACEHOLDER_IMAGE_RES)
                    .error(PLACEHOLDER_IMAGE_RES)
                    .fit()
                    .into(photoImageView);
        } else {
            Log.d(TAG, "bindUser: No user photo URL found.");

            Picasso.with(getContext())
                    .load(PLACEHOLDER_IMAGE_RES)
                    .error(PLACEHOLDER_IMAGE_RES)
                    .fit()
                    .into(photoImageView);
        }
    }

    public void setPublishing(final boolean publishing) {
        setBorderColor(publishing ? GREEN : RED);
    }

    private void setBorderColor(@NonNull final BorderColor borderColor) {
        final Drawable backgroundDrawable
                = ContextCompat.getDrawable(getContext(), R.drawable.card_background);

        backgroundDrawable.mutate()
                .setColorFilter(getColorInt(borderColor), PorterDuff.Mode.SRC_ATOP);

        setBackground(backgroundDrawable);
    }

    @ColorInt
    private int getColorInt(@NonNull final BorderColor borderColor) {
        switch (borderColor) {
            case GREEN:
                return ContextCompat.getColor(getContext(), R.color.colorAccent);
            case RED:
                return ContextCompat.getColor(getContext(), R.color.colorPrimary);
            default:
            case GREY:
                return Color.LTGRAY;
        }
    }

}
