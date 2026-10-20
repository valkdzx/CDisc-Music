package dev.valkdz.cdisc.disc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SearchQueryTest {

    @Test
    void flagsLeaveTheSearchTextAsTyped() {
        SearchQuery parsed = SearchQuery.parse("yt:madk1d  ДИНАСТИЯ --results=7 --version=instrumental");

        assertEquals("yt:madk1d  ДИНАСТИЯ", parsed.text());
        assertEquals(7, parsed.requestedResults());
        assertEquals("minus", parsed.version());
        assertNull(parsed.badFlag());
    }

    @Test
    void aLinkKeepsItsVersionAndTheOriginalAsksForNothing() {
        SearchQuery link = SearchQuery.parse("https://open.spotify.com/track/0i8YObgqnjZYoYJOQbwLHq?si=f3 --version=uncensored");
        assertEquals("https://open.spotify.com/track/0i8YObgqnjZYoYJOQbwLHq?si=f3", link.text());
        assertEquals("uncensored", link.version());

        assertNull(SearchQuery.parse("song --version=original").version());
        assertEquals("minus", SearchQuery.parse("--VERSION=Minus song").version());
        assertEquals("song", SearchQuery.parse("--VERSION=Minus song").text());
    }

    @Test
    void aValueWeDoNotKnowIsReported() {
        assertEquals("--results=11", SearchQuery.parse("song --results=11").badFlag());
        assertEquals("--results=", SearchQuery.parse("song --results=").badFlag());
        assertEquals("--version=acapella", SearchQuery.parse("song --version=acapella").badFlag());
    }

    @Test
    void theTrailingCountStillWorksAndAFlagWins() {
        SearchQuery old = SearchQuery.parse("yt:some song:10");
        assertEquals("yt:some song", old.text());
        assertEquals(10, old.requestedResults());

        SearchQuery both = SearchQuery.parse("Song:2 --results=3");
        assertEquals("Song:2", both.text());
        assertEquals(3, both.requestedResults());

        assertEquals(SearchQuery.UNSET, SearchQuery.parse("https://youtu.be/x:5").requestedResults());
        assertEquals("a--results=5", SearchQuery.parse("a--results=5").text());
    }
}
