package me.axieum.mcmod.authme.impl.gui;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.Text;

import me.axieum.mcmod.authme.api.gui.AuthScreen;
import me.axieum.mcmod.authme.api.util.SessionUtils;
import static me.axieum.mcmod.authme.impl.AuthMe.CONFIG;
import static me.axieum.mcmod.authme.impl.AuthMe.LOGGER;
import static me.axieum.mcmod.authme.impl.AuthMe.getConfig;

/**
 * A screen for handling offline user authentication.
 */
public class OfflineAuthScreen extends AuthScreen
{
    // The username and password text field widgets
    private TextFieldWidget usernameField;
    // The login button widget
    private ButtonWidget loginBtn;

    /**
     * Constructs a new offline authentication screen.
     *
     * @param parentScreen  parent (or last) screen that opened this screen
     * @param successScreen screen to be returned to after a successful login
     */
    public OfflineAuthScreen(Screen parentScreen, Screen successScreen)
    {
        super(Text.translatable("gui.authme.offline.title"), parentScreen, successScreen);
        this.closeOnSuccess = true;
    }

    @Override
    protected void init()
    {
        super.init();
        assert client != null;

        // Add a title
        TextWidget titleWidget = new TextWidget(width, height, title, textRenderer);
        titleWidget.setTextColor(0xffffff);
        titleWidget.setPosition(width / 2 - titleWidget.getWidth() / 2, height / 2 - titleWidget.getHeight() / 2 - 40);
        addDrawableChild(titleWidget);

        // Add a username text field
        addDrawableChild(
            usernameField = new TextFieldWidget(
                client.textRenderer,
                width / 2 - 100, height / 2 - 6, 200, 20,
                Text.translatable("gui.authme.offline.field.username")
            )
        );
        usernameField.setMaxLength(128);
        if (getConfig().methods.offline.lastUsername != null) {
            usernameField.setText(getConfig().methods.offline.lastUsername);
        }
        usernameField.setChangedListener(value -> loginBtn.active = isFormValid());

        // Add a label for the username field
        TextWidget labelWidget = new TextWidget(width, height, usernameField.getMessage(), textRenderer);
        labelWidget.setTextColor(0xdddddd);
        labelWidget.setPosition(
            width / 2 - labelWidget.getWidth() / 2 - 51,
            height / 2 - labelWidget.getHeight() / 2 - 17
        );
        addDrawableChild(labelWidget);

        // Add a login button to submit the form
        addDrawableChild(
            loginBtn = ButtonWidget.builder(
                Text.translatable("gui.authme.offline.button.login"),
                button -> login()
            ).dimensions(
                width / 2 - 100 - 2, height / 2 + 26, 100, 20
            ).build()
        );
        loginBtn.active = isFormValid();

        // Add a cancel button to abort the task
        addDrawableChild(
            ButtonWidget.builder(
                Text.translatable("gui.cancel"),
                button -> close()
            ).dimensions(
                width / 2 + 2, height / 2 + 26, 100, 20
            ).build()
        );
    }

    /**
     * Creates a new offline-mode session the provided username.
     */
    public void login()
    {
        assert client != null;

        // Check whether the form is valid
        if (!isFormValid()) return;

        // Disable the form fields while logging in
        usernameField.active = false;
        loginBtn.active = false;

        // Create and apply a new offline Minecraft session
        SessionUtils.setSession(SessionUtils.offline(usernameField.getText()));

        // Sync configuration with the last used username
        getConfig().methods.offline.lastUsername = usernameField.getText();
        CONFIG.save();

        // Add a toast that greets the player
        SystemToast.add(
            client.getToastManager(), SystemToast.Type.PERIODIC_NOTIFICATION,
            Text.translatable("gui.authme.toast.greeting", Text.literal(usernameField.getText())), null
        );

        // Mark the task as successful, in turn closing the screen
        LOGGER.info("Successfully logged in offline-mode!");
        success = true;
    }

    /**
     * Checks whether the form can be submitted, and hence logged in.
     *
     * @return true if the username field is valid
     */
    public boolean isFormValid()
    {
        return !usernameField.getText().isBlank();
    }
}
