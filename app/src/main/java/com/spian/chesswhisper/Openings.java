package com.spian.chesswhisper;

/** Opening choices: name + your moves in order (SAN, without + or #). */
public final class Openings {
    private Openings() { }

    public static final String[][] WHITE_NAMES = {
        {"none", "Engine decides"}, {"italian", "Italian Game"}, {"ruy", "Ruy Lopez"}, {"scotch", "Scotch Game"},
        {"vienna", "Vienna Game"}, {"kgambit", "King's Gambit"}, {"qg", "Queen's Gambit"},
        {"london", "London System"}, {"english", "English Opening"}};
    public static final String[][] VS_E4_NAMES = {
        {"none", "Engine decides"}, {"e5", "Open Game (e5)"}, {"sicilian", "Sicilian Defense"}, {"french", "French Defense"},
        {"caro", "Caro-Kann"}, {"scandi", "Scandinavian"}};
    public static final String[][] VS_D4_NAMES = {
        {"none", "Engine decides"}, {"qgd", "Queen's Gambit Declined"}, {"slav", "Slav Defense"},
        {"kid", "King's Indian"}, {"dutch", "Dutch Defense"}};

    public static String[] line(String key) {
        switch (key) {
            case "italian": return new String[]{"e4", "Nf3", "Bc4", "c3", "d3", "O-O", "Re1"};
            case "ruy": return new String[]{"e4", "Nf3", "Bb5", "O-O", "Re1", "c3", "d4"};
            case "scotch": return new String[]{"e4", "Nf3", "d4", "Nxd4", "Nc3", "Be2"};
            case "vienna": return new String[]{"e4", "Nc3", "Bc4", "d3", "Nf3"};
            case "kgambit": return new String[]{"e4", "f4", "Nf3", "Bc4", "O-O"};
            case "qg": return new String[]{"d4", "c4", "Nc3", "Nf3", "Bg5", "e3"};
            case "london": return new String[]{"d4", "Bf4", "e3", "Nf3", "c3", "Nbd2", "Bd3", "O-O"};
            case "english": return new String[]{"c4", "Nc3", "g3", "Bg2", "Nf3", "O-O"};
            case "e5": return new String[]{"e5", "Nc6", "Nf6", "Bc5", "d6", "O-O"};
            case "sicilian": return new String[]{"c5", "d6", "cxd4", "Nf6", "a6", "e5"};
            case "french": return new String[]{"e6", "d5", "Nf6", "Be7", "O-O", "c5"};
            case "caro": return new String[]{"c6", "d5", "Bf5", "e6", "Nd7", "Ngf6"};
            case "scandi": return new String[]{"d5", "Qxd5", "Qa5", "Nf6", "c6", "Bf5"};
            case "qgd": return new String[]{"d5", "e6", "Nf6", "Be7", "O-O", "Nbd7"};
            case "slav": return new String[]{"d5", "c6", "Nf6", "Bf5", "e6", "Nbd7"};
            case "kid": return new String[]{"Nf6", "g6", "Bg7", "d6", "O-O", "e5"};
            case "dutch": return new String[]{"f5", "Nf6", "e6", "Be7", "O-O", "d6"};
            default: return new String[0];
        }
    }
}
