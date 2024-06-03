package me.axieum.mcmod.authme.impl.gui;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.http.conn.ConnectTimeoutException;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import me.axieum.mcmod.authme.api.gui.AuthScreen;
import me.axieum.mcmod.authme.api.util.MicrosoftUtils;
import me.axieum.mcmod.authme.api.util.SessionUtils;
import static me.axieum.mcmod.authme.impl.AuthMe.LOGGER;

/**
 * A screen for handling user authentication via Microsoft.
 */
public class MicrosoftAuthScreen extends AuthScreen
{
    // The executor to run the login task on
    private ExecutorService executor = null;
    // The completable future for all Microsoft login tasks
    private CompletableFuture<Void> task = null;
    // The current progress/status of the login task
    private TextWidget statusWidget = null;
    // True if Microsoft should prompt to select an account
    private final boolean selectAccount;

    /**
     * Constructs a new authentication via Microsoft screen.
     *
     * @param parentScreen  parent (or last) screen that opened this screen
     * @param successScreen screen to be returned to after a successful login
     * @param selectAccount true if Microsoft should prompt to select an account
     */
    public MicrosoftAuthScreen(Screen parentScreen, Screen successScreen, boolean selectAccount)
    {
        super(Text.translatable("gui.authme.microsoft.title"), parentScreen, successScreen);
        this.selectAccount = selectAccount;
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
        titleWidget.setPosition(width / 2 - titleWidget.getWidth() / 2, height / 2 - titleWidget.getHeight() / 2 - 27);
        addDrawableChild(titleWidget);

        // Add a status message
        statusWidget = new TextWidget(width, height, title, textRenderer);
        statusWidget.setTextColor(0xdddddd);
        statusWidget.setPosition(
            width / 2 - statusWidget.getWidth() / 2, height / 2 - statusWidget.getHeight() / 2 - 1
        );
        addDrawableChild(statusWidget);

        // Add a cancel button to abort the task
        final ButtonWidget cancelBtn;
        addDrawableChild(
            cancelBtn = ButtonWidget.builder(
                Text.translatable("gui.cancel"),
                button -> close()
            ).dimensions(
                width / 2 - 50, height / 2 + 22, 100, 20
            ).build()
        );

        // Prevent the task from starting several times
        if (task != null) return;

        // Set the initial progress/status of the login task
        statusWidget.setMessage(Text.translatable("gui.authme.microsoft.status.checkBrowser"));

        // Prepare a new executor thread to run the login task on
        executor = Executors.newSingleThreadExecutor();

        // Start the login task
        task = MicrosoftUtils
            // Acquire a Microsoft auth code
            .acquireMSAuthCode(
                success -> Text.translatable("gui.authme.microsoft.browser").getString(),
                executor,
                selectAccount ? MicrosoftUtils.MicrosoftPrompt.SELECT_ACCOUNT : null
            )

            // Exchange the Microsoft auth code for an access token
            .thenComposeAsync(msAuthCode -> {
                statusWidget.setMessage(Text.translatable("gui.authme.microsoft.status.msAccessToken"));
                return MicrosoftUtils.acquireMSAccessToken(msAuthCode, executor);
            })

            // Exchange the Microsoft access token for an Xbox access token
            .thenComposeAsync(msAccessToken -> {
                statusWidget.setMessage(Text.translatable("gui.authme.microsoft.status.xboxAccessToken"));
                return MicrosoftUtils.acquireXboxAccessToken(msAccessToken, executor);
            })

            // Exchange the Xbox access token for an XSTS token
            .thenComposeAsync(xboxAccessToken -> {
                statusWidget.setMessage(Text.translatable("gui.authme.microsoft.status.xboxXstsToken"));
                return MicrosoftUtils.acquireXboxXstsToken(xboxAccessToken, executor);
            })

            // Exchange the Xbox XSTS token for a Minecraft access token
            .thenComposeAsync(xboxXstsData -> {
                statusWidget.setMessage(Text.translatable("gui.authme.microsoft.status.mcAccessToken"));
                return MicrosoftUtils.acquireMCAccessToken(
                    xboxXstsData.get("Token"), xboxXstsData.get("uhs"), executor
                );
            })

            // Build a new Minecraft session with the Minecraft access token
            .thenComposeAsync(mcToken -> {
                statusWidget.setMessage(Text.translatable("gui.authme.microsoft.status.mcProfile"));
                return MicrosoftUtils.login(mcToken, executor);
            })

            // Update the game session and greet the player
            .thenAccept(session -> {
                // Apply the new session
                SessionUtils.setSession(session);
                // Add a toast that greets the player
                SystemToast.add(
                    client.getToastManager(), SystemToast.Type.PERIODIC_NOTIFICATION,
                    Text.translatable("gui.authme.toast.greeting", Text.literal(session.getUsername())), null
                );
                // Mark the task as successful, in turn closing the screen
                LOGGER.info("Successfully logged in via Microsoft!");
                success = true;
            })

            // On any exception, update the status and cancel button
            .exceptionally(error -> {
                final String key;
                if (error.getCause() instanceof ConnectTimeoutException) {
                    key = "gui.authme.error.timeout";
                } else if ("NOT_FOUND: Not Found".equals(error.getCause().getMessage())) {
                    key = "gui.authme.error.notPurchased";
                } else {
                    key = "gui.authme.error.generic";
                }
                statusWidget.setMessage(Text.translatable(key).formatted(Formatting.RED));
                cancelBtn.setMessage(Text.translatable("gui.back"));
                return null; // return a default value
            });
    }

    @Override
    public void close()
    {
        // Cancel the login task if still running
        if (task != null && !task.isDone()) {
            task.cancel(true);
            executor.shutdownNow();
        }

        // Cascade the closing
        super.close();
    }
}
