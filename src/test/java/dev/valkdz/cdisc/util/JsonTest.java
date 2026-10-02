package dev.valkdz.cdisc.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class JsonTest {

    @Test
    void readsNestedValues() throws IOException {
        Json root = Json.parse("{\"a\":{\"b\":[1,2.5,\"x\",true,null]},\"s\":\"q\\\"\u00e9\"}");
        assertEquals(1, root.get("a").get("b").index(0).asInt(0));
        assertEquals(2.5, root.path("a").path("b").path(1).asDouble(0));
        assertEquals("x", root.get("a").get("b").get(2).text());
        assertTrue(root.get("a").get("b").get(3).asBoolean(false));
        assertTrue(root.get("a").get("b").get(4).isNull());
        assertEquals("q\"\u00e9", root.get("s").text());
        assertTrue(root.get("nope").isMissing());
        assertNull(root.get("nope").get("deeper").text());
        assertNull(root.get("a").text());
        assertEquals("", root.get("a").asText());
    }

    @Test
    void numbersInTextCount() throws IOException {
        Json root = Json.parse("{\"n\":\"42\",\"bad\":\"x\"}");
        assertEquals(42, root.get("n").asLong(0));
        assertEquals(7, root.get("bad").asLong(7));
    }

    @Test
    void writesWhatItReads() throws IOException {
        String text = "{\"a\":[1,2.5,\"x\\n\"],\"b\":{\"c\":false},\"d\":null}";
        assertEquals(text, Json.parse(text).toString());
        Json pretty = Json.parse(Json.parse(text).toPrettyString());
        assertEquals(text, pretty.toString());
    }

    @Test
    void builds() {
        Json body = Json.object().put("n", 3).put("t", "v").put("f", true);
        body.putObject("o").putArray("l").add("x").add(2);
        assertEquals("{\"n\":3,\"t\":\"v\",\"f\":true,\"o\":{\"l\":[\"x\",2]}}", body.toString());
    }

    @Test
    void lenientTakesCommentsAndTrailingCommas() throws IOException {
        Json root = Json.parseLenient("// head\n{ 'a': 1, # yaml style\n /* block */ \"b\": [1, 2,], }");
        assertEquals(1, root.get("a").asInt(0));
        assertEquals(2, root.get("b").size());
        assertThrows(IOException.class, () -> Json.parse("{'a': 1}"));
        assertThrows(IOException.class, () -> Json.parse("{\"a\": 1,}"));
    }

    @Test
    void rejectsGarbage() {
        assertThrows(IOException.class, () -> Json.parse("not json at all"));
        assertThrows(IOException.class, () -> Json.parse("{\"a\":"));
        assertThrows(IOException.class, () -> Json.parse("[1] 2"));
    }
}
