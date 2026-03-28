package com.pronote.client;

/**
 * Known Pronote appelfonction API function names.
 * These are passed as the {@code "id"} field in encrypted requests.
 */
public enum ApiFunction {
    FONCTION_PARAMETRES("FonctionParametres"),
    AUTHENTIFICATION("Authentification"),
    LIST_HOMEWORK("PageCahierDeTexte"),
    PAGE_TIMETABLE("PageEmploiDuTemps");

    private final String value;

    ApiFunction(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
