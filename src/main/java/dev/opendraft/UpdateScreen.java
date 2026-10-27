package dev.opendraft;

import com.mojang.blaze3d.Blaze3D;
import dev.opendraft.update.Release;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** What the OpenDraft button shows while there's an update to get, instead of the lobby. */
public final class UpdateScreen extends Screen {
    /** How long to wait for a check that's still going before opening the lobby anyway. */
    private static final int CHECK_WAIT_TICKS = 8 * 20;

    private final Screen parent;
    private Updater.State shown;
    private int waited;

    UpdateScreen(Screen parent) {
        super(Component.literal("OpenDraft Update"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        Minecraft mc = Minecraft.getInstance();
        shown = Updater.state;
        int cx = width / 2;
        int y = height / 2 + 30;
        List<Button> buttons = new ArrayList<>();
        switch (shown) {
            case AVAILABLE -> buttons.add(button("Update", b -> Updater.download()));
            case FAILED -> {
                buttons.add(button("Try Again", b -> Updater.download()));
                buttons.add(button("Releases Page", b -> Blaze3D.openUri(URI.create(Release.PAGE))));
            }
            case READY -> buttons.add(button("Quit Game", b -> mc.stop()));
            case STUCK -> {
                buttons.add(button("Open Mods Folder", b -> Blaze3D.openPath(Updater.ownJar.getParent())));
                buttons.add(button("Open Update Folder", b -> Blaze3D.openPath(Updater.dir)));
                buttons.add(button("Quit Game", b -> mc.stop()));
            }
            default -> {
            }
        }
        buttons.add(button("Back", b -> mc.gui.setScreen(parent)));
        for (Button b : buttons) {
            b.setPosition(cx - 100, y);
            addRenderableWidget(b);
            y += 24;
        }
    }

    private static Button button(String label, Button.OnPress press) {
        return Button.builder(Component.literal(label), press).size(200, 20).build();
    }

    @Override
    public void tick() {
        Updater.State now = Updater.state;
        if (!Updater.blocking()) {
            // The check found nothing newer, or couldn't be made.
            Minecraft.getInstance().gui.setScreen(new LobbyScreen());
            return;
        }
        if (now == Updater.State.CHECKING && ++waited > CHECK_WAIT_TICKS) {
            // Slow network: don't hold anyone up. Next time the button is pressed, the check has probably finished.
            Minecraft.getInstance().gui.setScreen(new LobbyScreen());
            return;
        }
        if (now != shown) rebuildWidgets();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        Font font = getFont();
        Release r = Updater.release;
        String version = r == null ? "" : r.version();
        String jar = r == null ? "" : r.jar();
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("OpenDraft Update").withStyle(ChatFormatting.BOLD));
        lines.add(Component.empty());
        switch (Updater.state) {
            case CHECKING -> lines.add(gray("Checking for updates..."));
            case AVAILABLE -> {
                lines.add(Component.literal("OpenDraft " + version + " is out, and you have " + OpenDraftClient.modVersion() + "."));
                lines.add(gray("Lobbies only take players on the same version, so you need it to play."));
            }
            case DOWNLOADING -> lines.add(Component.literal(String.format("Downloading OpenDraft %s... %.1f MB", version, Updater.downloaded / 1048576.0)));
            case FAILED -> {
                lines.add(Component.literal("The download didn't work: " + Updater.problem).withStyle(ChatFormatting.RED));
                lines.add(gray("Try again, or get it by hand from the releases page."));
            }
            case READY -> {
                lines.add(Component.literal("OpenDraft " + version + " is downloaded.").withStyle(ChatFormatting.GREEN));
                lines.add(gray("Close the game and start it again to finish updating."));
            }
            case STUCK -> {
                lines.add(Component.literal("OpenDraft " + version + " is downloaded, but it couldn't be put in place").withStyle(ChatFormatting.YELLOW));
                lines.add(Component.literal("when the game closed.").withStyle(ChatFormatting.YELLOW));
                lines.add(gray("Close the game, then in your mods folder delete " + Updater.ownJar.getFileName()));
                lines.add(gray("and move " + jar + " there from the update folder."));
            }
            default -> {
            }
        }
        int y = height / 2 + 20 - lines.size() * 12;
        for (Component c : lines) {
            g.text(font, c, (width - font.width(c)) / 2, y, 0xFFFFFFFF, true);
            y += 12;
        }
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    private static Component gray(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GRAY);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }

    public boolean isPauseScreen() {
        return false;
    }
}
