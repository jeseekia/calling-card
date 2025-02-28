package com.github.stkent.callingcard;

import android.content.Context;
import android.content.res.TypedArray;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import butterknife.Bind;
import butterknife.ButterKnife;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

public final class UsersView extends LinearLayout implements OnClickListener {

    public interface UserClickListener {
        void onUserClick(@NonNull final User user);
    }

    @Bind(R.id.empty_state_view)
    protected TextView emptyStateLabel;

    @Bind(R.id.user_view_container)
    protected ViewGroup userViewContainer;

    @NonNull
    private final List<User> displayedUsers = new ArrayList<>();

    @Nullable
    private UserClickListener userClickListener;

    public UsersView(@NonNull final Context context) {
        this(context, null);
    }

    public UsersView(@NonNull final Context context, @Nullable final AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public UsersView(
            @NonNull final Context context,
            @Nullable final AttributeSet attrs,
            final int defStyleAttr) {

        super(context, attrs, defStyleAttr);

        setLayoutParams(new ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        setOrientation(VERTICAL);

        LayoutInflater.from(context).inflate(R.layout.include_users_view, this, true);
        ButterKnife.bind(this);

        final TypedArray typedArray
                = context.getTheme().obtainStyledAttributes(attrs, R.styleable.UsersView, 0, 0);

        final String emptyStateText = typedArray.getString(R.styleable.UsersView_empty_state_text);
        emptyStateLabel.setText(emptyStateText);

        typedArray.recycle();
    }

    @Override
    public void onClick(final View v) {
        final User clickedUser = (User) v.getTag();

        if (userClickListener != null) {
            userClickListener.onUserClick(clickedUser);
        }
    }

    public void setUserClickListener(@NonNull final UserClickListener userClickListener) {
        this.userClickListener = userClickListener;
    }

    public void addUser(@NonNull final User userToAdd) {
        if (userToAdd.isValid() && !displayedUsers.contains(userToAdd)) {
            final UserView userView = new UserView(getContext());
            userView.bindUser(userToAdd);
            userView.setTag(userToAdd);
            userView.setOnClickListener(this);

            if (userViewContainer.getChildCount() > 0) {
                userViewContainer.addView(
                        LayoutInflater.from(getContext()).inflate(
                                R.layout.include_users_view_spacer,
                                userViewContainer,
                                false));
            }

            userViewContainer.addView(userView);

            displayedUsers.add(userToAdd);

            updateEmptyStateVisibility();
        }
    }

    public void setUsers(@NonNull final List<User> users) {
        removeAllUsers();

        for (final User user: users) {
            addUser(user);
        }
    }

    public void removeAllUsers() {
        userViewContainer.removeAllViews();

        displayedUsers.clear();

        updateEmptyStateVisibility();
    }

    private void updateEmptyStateVisibility() {
        emptyStateLabel.setVisibility(displayedUsers.size() == 0 ? VISIBLE : GONE);
    }

}
