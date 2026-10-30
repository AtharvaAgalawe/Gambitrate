package com.gambitrate.ingest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PgnParserTest {

    private final PgnParser parser = new PgnParser();

    @Test
    void parsesMovesClocksAndOpeningFromPgn() {
        ParsedPgn pgn = parser.parse("""
                [Event "Live Chess"]
                [Site "Chess.com"]
                [Date "2024.01.01"]
                [White "FixtureUser"]
                [Black "Opponent"]
                [Result "1-0"]
                [ECO "C20"]
                [ECOUrl "https://www.chess.com/openings/Kings-Pawn-Opening"]
                [TimeControl "600+5"]
                [Termination "FixtureUser won by checkmate"]

                1. e4 {[%clk 0:09:59.9]} e5 {[%clk 0:09:59.8]} 2. Bc4 {[%clk 0:09:55.4]}
                Nc6 {[%clk 0:09:54.1]} 3. Qh5 {[%clk 0:09:50.2]} Nf6 {[%clk 0:09:47.9]}
                4. Qxf7# {[%clk 0:09:41.6]} 1-0
                """);

        assertThat(pgn.ecoCode()).isEqualTo("C20");
        assertThat(pgn.openingName()).isEqualTo("Kings Pawn Opening");
        assertThat(pgn.movesSan()).isEqualTo("e4 e5 Bc4 Nc6 Qh5 Nf6 Qxf7#");
        assertThat(pgn.moves()).hasSize(7);
        assertThat(pgn.moves().get(0).san()).isEqualTo("e4");
        assertThat(pgn.moves().get(0).clockRemainingMs()).isEqualTo(599_900L);
        assertThat(pgn.moves().get(6).san()).isEqualTo("Qxf7#");
        assertThat(pgn.moves().get(6).clockRemainingMs()).isEqualTo(581_600L);
    }

    @Test
    void keepsMovesWithoutClockAnnotations() {
        ParsedPgn pgn = parser.parse("""
                [Event "Live Chess"]
                [White "FixtureUser"]
                [Black "Opponent"]
                [Result "1-0"]

                1. e4 e5 2. Nf3 Nc6 1-0
                """);

        assertThat(pgn.moves()).hasSize(4);
        assertThat(pgn.moves()).allSatisfy(move -> assertThat(move.clockRemainingMs()).isNull());
    }
}
