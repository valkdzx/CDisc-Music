package dev.valkdz.cdisc.gui.dialog;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.audio.LavaPlayerManager;
import dev.valkdz.cdisc.gui.PlayerActions;
import dev.valkdz.cdisc.lyrics.LyricsPrefs;
import dev.valkdz.cdisc.speaker.SpeakerSettings;
import dev.valkdz.cdisc.util.PlayerPrefs;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
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

    private record Shown(long positionMs, int volume, int mine,
                         boolean lyrics, boolean messages) {
    }

    private OptionsDialog() {
    }

    static void open(Main plugin, Player player, Block block) {
        LavaPlayerManager apm = plugin.getAudioPlayerManager();
        LavaPlayerManager.PlaybackInfo info = apm.getPlaybackInfo(block);
        SpeakerSettings speaker = SpeakerSettings.of(block);

        Shown shown = new Shown(
                info == null ? 0L : info.position(),
                speaker.volume(),
                PlayerPrefs.effectiveLocalVolume(player, speaker.volume()),
                plugin.cdiscConfig().isLyricsEnabled() && LyricsPrefs.isEnabled(block),
                PlayerPrefs.showsTrackMessages(player));

        List<DialogInput> inputs = new ArrayList<>();

        if (info != null && !info.live() && info.duration() > 0) {
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

        inputs.add(DialogInput.numberRange(KEY_VOLUME,
                        PlayerDialog.text(plugin, player, "gui.dialog.input_volume"), 0f, 100f)
                .initial((float) shown.volume())
                .step(VOLUME_STEP)
                .width(INPUT_WIDTH)
                .labelFormat("%s: %s")
                .build());

        inputs.add(DialogInput.numberRange(KEY_MINE,
                        PlayerDialog.text(plugin, player, "gui.dialog.input_mine"), 0f, 100f)
                .initial((float) shown.mine())
                .step(VOLUME_STEP)
                .width(INPUT_WIDTH)
                .labelFormat("%s: %s")
                .build());

        if (plugin.cdiscConfig().isLyricsEnabled()) {
            inputs.add(DialogInput.bool(KEY_LYRICS,
                            PlayerDialog.text(plugin, player, "gui.dialog.input_lyrics"))
                    .initial(shown.lyrics())
                    .build());
        }
        inputs.add(DialogInput.bool(KEY_MESSAGES,
                        PlayerDialog.text(plugin, player, "gui.dialog.input_messages"))
                .initial(shown.messages())
                .build());

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
                PlayerDialog.onMainThread(plugin, () -> {
                    apply(plugin, player, block, view, shown);
                    PlayerDialog.open(plugin, player, block);
                }));

        ActionButton back = PlayerDialog.button(plugin, player, "back", (view, listener) ->
                PlayerDialog.onMainThread(plugin, () -> PlayerDialog.open(plugin, player, block)));

        PlayerDialog.audience(player).showDialog(Dialog.create(factory -> factory.empty()
                .base(base)
                .type(DialogType.multiAction(List.of(apply))
                        .exitAction(back)
                        .columns(1)
                        .build())));
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

        Boolean lyrics = view.getBoolean(KEY_LYRICS);
        if (lyrics != null && lyrics != shown.lyrics()) {
            actions.setLyrics(player, block, lyrics);
        }

        Boolean messages = view.getBoolean(KEY_MESSAGES);
        if (messages != null && messages != shown.messages()) {
            actions.setTrackMessages(player, messages);
        }
    }
}
