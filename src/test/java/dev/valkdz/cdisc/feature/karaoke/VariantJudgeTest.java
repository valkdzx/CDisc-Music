package dev.valkdz.cdisc.feature.karaoke;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VariantJudgeTest {

    private static final long SONG = 180_000;
    private static final long[] WORDS = {8_000, 21_300, 35_200, 36_000, 46_000, 47_900, 49_400, 62_700};

    @Test
    void mutedWordsProveTheOriginalCensoredFromAnyUpload() {
        VariantJudge.Heard upload = heard(VariantFinder.YOUTUBE, "Белочка - KSB Music", 1, spots(-10, 0, 1, 2));
        VariantJudge.Verdict verdict = VariantJudge.judge(List.of(upload), SONG);

        assertTrue(verdict.censored());
        assertSame(upload, verdict.uncensored());
        assertEquals(3, verdict.words());
    }

    @Test
    void replacedWordsNeedSoundCloudOrAMarker() {
        VariantJudge.Heard upload = heard(VariantFinder.YOUTUBE, "madk1d - барыга", 1, spots(0, 0, 1, 2, 3, 4, 5, 6, 7));
        assertFalse(VariantJudge.judge(List.of(upload), SONG).censored());

        VariantJudge.Heard marked = heard(VariantFinder.YOUTUBE, "madk1d - барыга (без цензуры)", 1,
                spots(0, 0, 1, 2, 3, 4, 5, 6, 7));
        assertTrue(VariantJudge.judge(List.of(marked), SONG).censored());
    }

    @Test
    void anUploadAgreeingWithSoundCloudIsPickedForItsSpeed() {
        VariantJudge.Heard upload = heard(VariantFinder.YOUTUBE, "madk1d - барыга", 1, spots(0, 0, 1, 2, 3, 4, 5, 6, 7));
        VariantJudge.Heard soundCloud = heard(VariantFinder.SOUNDCLOUD, "барыга", 1.0094, spots(0, 0, 1, 2, 3, 6));

        VariantJudge.Verdict verdict = VariantJudge.judge(List.of(soundCloud, upload), SONG);

        assertTrue(verdict.censored());
        assertSame(upload, verdict.uncensored());
        assertEquals(8, verdict.words());
    }

    @Test
    void aSoundCloudCopyThatSingsTheSameClearsTheOriginal() {
        VariantJudge.Heard same = heard(VariantFinder.SOUNDCLOUD, "барыга", 1, List.of());
        VariantJudge.Heard odd = heard(VariantFinder.YOUTUBE, "барыга (без цензуры)", 1, spots(-10, 0, 1, 2));
        assertFalse(VariantJudge.judge(List.of(odd, same), SONG).censored());
    }

    @Test
    void aCleanOriginalIsRecognisedFromAFaithfulSoundCloudCopy() {
        VariantJudge.Heard same = heard(VariantFinder.SOUNDCLOUD, "ДИНАСТИЯ", 1, List.of());
        VariantJudge.Heard censored = heard(VariantFinder.YOUTUBE, "ДИНАСТИЯ", 1, spots(12, 0, 1));
        List<VariantJudge.Heard> heard = List.of(censored, same);

        assertFalse(VariantJudge.judge(heard, SONG).censored());
        assertTrue(VariantJudge.cleanCopy(same, heard));
        assertFalse(VariantJudge.cleanCopy(censored, heard));
    }

    @Test
    void anUploadThatLostTheWordsItselfIsNotUncensored() {
        VariantJudge.Heard censoredCopy = heard(VariantFinder.SOUNDCLOUD, "барыга", 1, spots(12, 0, 1, 2));
        assertFalse(VariantJudge.judge(List.of(censoredCopy), SONG).censored());
    }

    @Test
    void aVerseSungAnotherWayIsATakeNotCensorship() {
        List<VocalDiff.Spot> verse = new ArrayList<>();
        for (long at = 89_000; at < 101_000; at += 800) verse.add(new VocalDiff.Spot(at, 300, 0.5, 0));
        verse.add(new VocalDiff.Spot(152_400, 480, 2, 60));
        VariantJudge.Heard altEdit = heard(VariantFinder.SOUNDCLOUD, "ДИНАСТИЯ", 1, verse);
        VariantJudge.Heard reupload = heard(VariantFinder.SOUNDCLOUD, "ДИНАСТИЯ", 1,
                List.of(new VocalDiff.Spot(91_680, 220, 0.4, 0), new VocalDiff.Spot(93_720, 360, 0.4, 0)));

        assertFalse(VariantJudge.judge(List.of(reupload, altEdit), SONG).censored());
    }

    @Test
    void aDifferentTakeIsNotCompared() {
        List<VocalDiff.Spot> everywhere = new ArrayList<>();
        for (int i = 0; i < 40; i++) everywhere.add(new VocalDiff.Spot(i * 4_000L, 600, 1, -10));
        VariantJudge.Heard take = heard(VariantFinder.SOUNDCLOUD, "барыга", 1, everywhere);
        assertFalse(VariantJudge.judge(List.of(take), SONG).censored());
    }

    private static List<VocalDiff.Spot> spots(double levelDb, int... words) {
        List<VocalDiff.Spot> spots = new ArrayList<>();
        for (int word : words) spots.add(new VocalDiff.Spot(WORDS[word], 400, 0.5, levelDb));
        return spots;
    }

    private static VariantJudge.Heard heard(String source, String title, double speed, List<VocalDiff.Spot> spots) {
        BeatMatch.Result match = new BeatMatch.Result(true, true, -170, speed, 0.5, 4, 1, 0.5);
        return new VariantJudge.Heard(new VariantFinder.Hit(source, source + ":" + title + speed, title, "madk1d", SONG),
                match, spots);
    }
}
