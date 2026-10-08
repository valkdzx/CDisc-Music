package dev.valkdz.cdisc.api;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.feature.speaker.SpeakerSettings;
import dev.valkdz.cdisc.jukebox.PlaybackManager;
import dev.valkdz.cdisc.jukebox.queue.RepeatMode;
import dev.valkdz.cdisc.util.Tasks;
import org.bukkit.block.Block;

import java.util.Optional;

final class ApiJukebox implements JukeboxControl {

    private final Main plugin;
    private final Block block;

    ApiJukebox(Main plugin, Block block) {
        this.plugin = plugin;
        this.block = block;
    }

    private static PlaybackManager apm() {
        PlaybackManager apm = CDiscApi.manager();
        if (apm == null) throw new IllegalStateException("CDisc is not enabled");
        return apm;
    }

    private void onBlock(Runnable work) {
        Tasks.inRegion(plugin, block, work);
    }

    @Override
    public Block block() {
        return block;
    }

    @Override
    public boolean isActive() {
        return apm().hasActiveSession(block);
    }

    @Override
    public boolean isPaused() {
        return apm().isPaused(block);
    }

    @Override
    public Optional<NowPlaying> nowPlaying() {
        return CDiscApi.nowPlaying(block);
    }

    @Override
    public int queueSize() {
        return apm().queueSize(block);
    }

    @Override
    public QueueControl queue() {
        return new ApiQueue(plugin, block);
    }

    @Override
    public void play(String source) {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("source is empty");
        apm().startPlaying(block, source.trim());
    }

    @Override
    public void resumeQueue() {
        apm();
        onBlock(() -> plugin.getPlayerActions().startFromIdle(block, 0));
    }

    @Override
    public void stop() {
        PlaybackManager apm = apm();
        onBlock(() -> {
            if (apm.hasActiveSession(block)) apm.stopPlaying(block, apm.getGeneration(block));
        });
    }

    @Override
    public void pause() {
        apm().setPaused(block, true);
    }

    @Override
    public void resume() {
        apm().setPaused(block, false);
    }

    @Override
    public void skip() {
        PlaybackManager apm = apm();
        onBlock(() -> apm.skipToNext(block));
    }

    @Override
    public void previous() {
        PlaybackManager apm = apm();
        onBlock(() -> apm.skipToPrevious(block));
    }

    @Override
    public void seek(long positionMs) {
        apm().seekTo(block, positionMs);
    }

    @Override
    public Repeat repeat() {
        return Repeat.valueOf(apm().getRepeatMode(block).name());
    }

    @Override
    public void repeat(Repeat mode) {
        apm().setRepeatMode(block, RepeatMode.valueOf(mode.name()));
    }

    @Override
    public boolean shuffle() {
        return apm().isShuffle(block);
    }

    @Override
    public void shuffle(boolean on) {
        apm().setShuffle(block, on);
    }

    @Override
    public int volume() {
        return SpeakerSettings.of(block).volume();
    }

    @Override
    public void volume(int volume) {
        apm();
        onBlock(() -> plugin.getPlayerActions().setSpeakerVolume(block, volume));
    }
}
