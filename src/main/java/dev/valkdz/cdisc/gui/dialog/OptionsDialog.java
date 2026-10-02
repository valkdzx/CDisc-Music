package dev.valkdz.cdisc.gui.dialog;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.gui.PlayerActions;
import dev.valkdz.cdisc.jukebox.PlaybackManager;
import dev.valkdz.cdisc.lyrics.LyricsMode;
import dev.valkdz.cdisc.lyrics.LyricsPrefs;
import dev.valkdz.cdisc.permission.Action;
import dev.valkdz.cdisc.speaker.SpeakerSettings;
import dev.valkdz.cdisc.util.PlayerPrefs;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

final class OptionsDialog {

    private static final int INPUT_WIDTH = 320;

    private static final float VOLUME_STEP = 5f;

    private static final String KEY_POSITION = "position";
    private static final String KEY_TIMECODE = "timecode";
    private static final String KEY_VOLUME = "volume";
    private static final String KEY_MINE = "mine";
    private static final String KEY_LYRICS = "lyrics";
    private static final String KEY_MESSAGES = "messages";
    private static final String KEY_CROSSFADE = "crossfade";

    private record Shown(long positionMs, int volume, int mine,
                         LyricsMode lyrics, boolean messages, boolean crossfade) {
    }

    private OptionsDialog() {
    }

    static void open(Main plugin, Player player, Block block) {
        PlaybackManager apm = plugin.getAudioPlayerManager();
        PlaybackManager.PlaybackInfo info = apm.getPlaybackInfo(block);
        SpeakerSettings speaker = SpeakerSettings.of(block);

        Shown shown = new Shown(
                info == null ? 0L : info.position(),
                speaker.volume(),
                PlayerPrefs.effectiveLocalVolume(player, speaker.volume()),
                LyricsPrefs.mode(player),
                PlayerPrefs.showsTrackMessages(player),
                apm.getQueue(block) == null || apm.getQueue(block).isCrossfade());

        List<DialogInput> inputs = new ArrayList<>();

        if (info != null && !info.live() && info.duration() > 0
                && may(plugin, player, Action.PLAYER_SEEK)) {
            float seconds = Math.max(1f, info.duration() / 1000f);
            inputs.add(DialogInput.numberRange(KEY_POSITION,
                            PlayerDialog.text(plugin, player, "gui.dialog.input_position"),
                            0f, seconds)
                    .initial(Math.min(seconds, shown.positionMs() / 1000f))
                    .step(1f)
                    .width(INPUT_WIDTH)
                    .labelFormat("%s: %s")
                    .build());
            inputs.add(DialogInput.text(KEY_TIMECODE,
                            PlayerDialog.text(plugin, player, "gui.dialog.input_timecode"))
                    .initial("")
                    .maxLength(9)
                    .width(INPUT_WIDTH)
                    .build());
        }

        if (may(plugin, player, Action.PLAYER_VOLUME)) {
            inputs.add(DialogInput.numberRange(KEY_VOLUME,
                            PlayerDialog.text(plugin, player, "gui.dialog.input_volume"), 0f, 100f)
                    .initial((float) shown.volume())
                    .step(VOLUME_STEP)
                    .width(INPUT_WIDTH)
                    .labelFormat("%s: %s")
                    .build());
        }

        if (may(plugin, player, Action.PLAYER_LOCAL_VOLUME)) {
            inputs.add(DialogInput.numberRange(KEY_MINE,
                            PlayerDialog.text(plugin, player, "gui.dialog.input_mine"), 0f, 100f)
                    .initial((float) shown.mine())
                    .step(VOLUME_STEP)
                    .width(INPUT_WIDTH)
                    .labelFormat("%s: %s")
                    .build());
        }

        if (plugin.cdiscConfig().isLyricsEnabled() && may(plugin, player, Action.LYRICS_TOGGLE)) {
            List<SingleOptionDialogInput.OptionEntry> modes = new ArrayList<>();
            for (LyricsMode mode : LyricsMode.values()) {
                modes.add(SingleOptionDialogInput.OptionEntry.create(mode.key(),
                        PlayerDialog.text(plugin, player, mode.messageKey()),
                        mode == shown.lyrics()));
            }
            inputs.add(DialogInput.singleOption(KEY_LYRICS,
                            PlayerDialog.text(plugin, player, "gui.dialog.input_lyrics"), modes)
                    .width(INPUT_WIDTH)
                    .build());
        }
        if (may(plugin, player, Action.PLAYER_MESSAGES)) {
            inputs.add(DialogInput.bool(KEY_MESSAGES,
                            PlayerDialog.text(plugin, player, "gui.dialog.input_messages"))
                    .initial(shown.messages())
                    .build());
        }

        if (apm.crossfadeSeconds() > 0 && may(plugin, player, Action.QUEUE_CROSSFADE)) {
            inputs.add(DialogInput.bool(KEY_CROSSFADE,
                            PlayerDialog.text(plugin, player, "gui.dialog.input_crossfade",
                                    String.valueOf(apm.crossfadeSeconds())))
                    .initial(shown.crossfade())
                    .build());
        }

        List<DialogBody> body = new ArrayList<>();
        if (info != null) {
            body.add(DialogBody.plainMessage(PlayerDialog.legacy("§f" + info.title())));
        }
        body.add(DialogBody.plainMessage(
                PlayerDialog.text(plugin, player, "gui.dialog.input_hint")));

        DialogBase base = DialogBase.builder(
                        PlayerDialog.text(plugin, player, "gui.dialog.options"))

                .canCloseWithEscape(true)
                .pause(false)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .body(body)
                .inputs(inputs)
                .build();

        ActionButton apply = PlayerDialog.button(plugin, player, "apply", (view, listener) ->
                PlayerDialog.onMainThread(plugin, player, () -> {
                    apply(plugin, player, block, view, shown);
                    PlayerDialog.open(plugin, player, block);
                }));

        ActionButton back = PlayerDialog.button(plugin, player, "back", (view, listener) ->
                PlayerDialog.onMainThread(plugin, player, () -> PlayerDialog.open(plugin, player, block)));

        PlayerDialog.audience(player).showDialog(Dialog.create(factory -> factory.empty()
                .base(base)
                .type(DialogType.multiAction(List.of(apply))
                        .exitAction(back)
                        .columns(1)
                        .build())));
    }

    private static boolean may(Main plugin, Player player, Action action) {
        return plugin.getPermissions().allows(player, action);
    }

    private static void apply(Main plugin, Player player, Block block,
                              DialogResponseView view, Shown shown) {
        PlayerActions actions = plugin.getPlayerActions();

        String typed = view.getText(KEY_TIMECODE);
        boolean sought = typed != null && !typed.isBlank()
                && actions.seekToTimecode(player, block, typed);

        if (!sought) {
            Float position = view.getFloat(KEY_POSITION);
            if (position != null) {
                long wanted = (long) (position * 1000L);

                if (Math.abs(wanted - shown.positionMs()) > 1000L) {
                    actions.seekTo(player, block, wanted);
                }
            }
        }

        Float volume = view.getFloat(KEY_VOLUME);
        if (volume != null && Math.round(volume) != shown.volume()) {
            actions.setSpeakerVolume(block, Math.round(volume));
        }

        Float own = view.getFloat(KEY_MINE);
        if (own != null && Math.round(own) != shown.mine()) {
            actions.setLocalVolume(player, block, Math.round(own));
        }

        LyricsMode lyrics = LyricsMode.ofKey(view.getText(KEY_LYRICS));
        if (lyrics != null && lyrics != shown.lyrics()) {
            actions.setLyrics(player, block, lyrics);
        }

        Boolean messages = view.getBoolean(KEY_MESSAGES);
        if (messages != null && messages != shown.messages()) {
            actions.setTrackMessages(player, messages);
        }

        Boolean crossfade = view.getBoolean(KEY_CROSSFADE);
        if (crossfade != null && crossfade != shown.crossfade()) {
            actions.setCrossfade(block, crossfade);
        }
    }
}
