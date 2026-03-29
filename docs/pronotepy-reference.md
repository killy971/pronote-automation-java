# Pronote API Reference — Extracted from pronotepy

Derived from `vendor/pronotepy` (source: https://github.com/bain3/pronotepy).
When adding a new scraper, **read this document first**, then verify details
against `vendor/pronotepy/pronotepy/clients.py` and `dataClasses.py` for edge cases.

---

## Quick Reference — All API Functions

| Function Name | Onglet | Purpose |
|---------------|--------|---------|
| `FonctionParametres` | — | Init session, get school year data |
| `Identification` | — | Send username (step 1 of auth) |
| `Authentification` | — | Send challenge response (step 2 of auth) |
| `ParametresUtilisateur` | — | Get user resource + authorized onglets |
| `Navigation` | 7 | Keep-alive ping |
| `JetonAppliMobile` | 7 | Request QR code token |
| `PageActualites` | 8 | News and surveys |
| `SaisieActualites` | 8 | Mark news as read |
| `PageMenus` | 10 | School canteen menus |
| `PageBulletins` | 13 | Report cards |
| `PageEmploiDuTemps` | 16 | Timetable (weekly) |
| `GenerationPDF` | 16 | Generate timetable PDF |
| `PageInfosPerso` | 16 / 49 | ICal URL / personal info |
| `PagePresence` | 19 | Absences, delays, punishments |
| `PageEquipePedagogique` | 37 | Teaching staff list |
| `PageCahierDeTexte` | 88 | Homework list |
| `SaisieTAFFaitEleve` | 88 | Mark homework done |
| `PageCahierDeTexte` | 89 | Lesson content |
| `FicheEleve` | 105 | Student details (VieScolaire) |
| `ListeRessources` | 105 | Students in a class |
| `ListeMessagerie` | 131 | List discussions |
| `ListeMessages` | 131 | Messages in a discussion |
| `SaisieMessage` | 131 | Send/reply/delete message |
| `SaisiePublicMessage` | 131 | Get participants/recipients of message |
| `ListeRessourcesPourCommunication` | 131 | Available messaging recipients |
| `DernieresNotes` | 198 | Grades and subject averages |
| `DernieresEvaluations` | 201 | Competency evaluations |
| `SecurisationCompteDoubleAuth` | — | 2FA PIN verification and device registration |

---

## Authentication Flow

### Step 1 — GET HTML page, parse attributes

`GET {pronote_url}` (e.g. `parent.html`)

Parse the `onload` attribute of `id="id_body"` using regex `Start\s*\({(?P<param>[^}]*)}\)`.
Split on `,` then split each token on `:` to produce a key/value dict.

| Key | Meaning |
|-----|---------|
| `h` | Session handle (used as `session` in all requests) |
| `a` | Account/space type (used as `genreEspace` and in URL path) |
| `http` | Boolean — if `true`, RSA-encrypt `aes_iv_temp`; if `false`, send raw |
| `CrA` | Boolean — encrypt all subsequent requests |
| `CoA` | Boolean — compress all subsequent requests |

### Step 2 — FonctionParametres

Generate 16 random bytes (`aes_iv_temp`).

If `http == true`: RSA-1024 PKCS#1 v1.5 encrypt `aes_iv_temp`, then base64 → `Uuid`.
If `http == false`: base64-encode raw `aes_iv_temp` → `Uuid`.

**RSA constants (hardcoded in `eleve.js`):**
```
modulo   = 130337874517286041778445012253514395801341480334668979416920989365464528904618150245388048105865059387076357492684573172203245221386376405947824377827224846860699130638566643129067735803555082190977267155957271492183684665050351182476506458843580431717209261903043895605014125081521285387341454154194253026277
exponent = 65537
```

POST body:
```json
{ "data": { "Uuid": "<base64>", "identifiantNav": "<client_id or null>" } }
```

Encryption state at this call: `key = MD5("")`, `iv = 16 null bytes`.
After response: apply `decryption_change` → `iv = MD5(aes_iv_temp)`.

Response key fields:
- `dataSec.data.General.PremierLundi.V` — first Monday of school year (`DD/MM/YYYY`)
- `dataSec.data.General.DerniereDate.V` — last day of school year
- `dataSec.data.General.ListePeriodes` — list of periods
- `dataSec.data.General.ListeHeures.V` — time slot start times
- `dataSec.data.General.ListeHeuresFin.V` — time slot end times
- `dataSec.data.identifiantNav` — client identifier to persist

### Step 3 — Identification

```json
{
  "genreConnexion": 0,
  "genreEspace": <int(a)>,
  "identifiant": "<username>",
  "pourENT": false,
  "enConnexionAuto": false,
  "demandeConnexionAuto": false,
  "demandeConnexionAppliMobile": false,
  "demandeConnexionAppliMobileJeton": false,
  "enConnexionAppliMobile": false,
  "uuidAppliMobile": "",
  "loginTokenSAV": ""
}
```

Response:
- `dataSec.data.challenge` — AES-encrypted hex string
- `dataSec.data.alea` — server random padding (may be absent/empty)
- `dataSec.data.modeCompLog` — if true, lowercase username before key derivation
- `dataSec.data.modeCompMdp` — if true, lowercase password before key derivation

### Step 4 — Key derivation

```
alea         = response["alea"] or ""
motdepasse   = SHA256(alea + password).hexdigest().upper()
aes_key      = MD5(username + motdepasse)
```

Decrypt `challenge` hex with that key/IV.
Apply `_enleverAlea`: keep only **even-indexed characters** of the decrypted string.
Re-encrypt the result → `ch` (hex).

### Step 5 — Authentification

```json
{ "connexion": 0, "challenge": "<ch hex>", "espace": <int(a)> }
```

Response: `dataSec.data.cle` — AES-encrypted session key material.

### Step 6 — after_auth key derivation

1. AES-decrypt `dataSec.data.cle` with the auth key.
2. The decrypted bytes decode to a comma-separated decimal string, e.g. `"123,45,67,..."`.
3. Convert to a `byte[]` by parsing each decimal number.
4. MD5-hash those bytes → the **new session AES key** for all subsequent calls.
5. Cookies from the HTTP response are captured (if not already set from ENT).

### Step 7 — ParametresUtilisateur

POST with no params. Response:
- `dataSec.data.ressource` — user's resource descriptor (id, name, children list for parent)
- `dataSec.data.listeOnglets` — nested list of authorized onglet numbers (flatten recursively)

---

## Request Envelope Structure

Every post-auth call is a POST to:
```
{root_site}/appelfonction/{a}/{h}/{encrypted_order_number}
```

`encrypted_order_number` = `AES_encrypt(str(request_number))` as hex.
`request_number` starts at 1, increments by +2 per request.

JSON body:
```json
{
  "session": <int(h)>,
  "no":      "<AES(request_number) hex>",
  "id":      "<FunctionName>",
  "dataSec": <payload>
}
```

Payload assembled as:
```json
{
  "Signature": { "onglet": <onglet_number> },
  "data":      { <function-specific params> }
}
```

If `CoA` (compress): JSON → hex → zlib(level=6, strip 2-byte header + 4-byte trailer) → hex UPPERCASE.
If `CrA` (encrypt): AES-CBC encrypt → hex UPPERCASE.

---

## Parent Account Scoping

Two independent mechanisms — they are additive, not interchangeable:

### `Signature.membre` (handled automatically by `PronoteHttpClient`)

Every post-auth onglet call includes:
```json
"Signature": { "onglet": <n>, "membre": { "N": "<child_N>", "G": 4 } }
```

### `ressource` in `data` params (scraper must add explicitly)

Required by some API functions (not all). APIs confirmed to need it:
- `PageEmploiDuTemps` — `"ressource": { "N": "<childId>", "G": 4 }`
- `GenerationPDF` — same

APIs that do NOT need the explicit `ressource` param:
- `PageCahierDeTexte` (onglets 88/89)
- `DernieresNotes` (198)
- `DernieresEvaluations` (201)
- `PagePresence` (19)
- `PageActualites` (8)

---

## `_T` Type Field Values

| `_T` | Meaning | `V` format |
|------|---------|------------|
| `7` | Datetime | `"DD/MM/YYYY HH:MM:SS"` |
| `8` | Week range | `"[weekFrom..weekTo]"` (school-year integer week numbers) |
| `26` | Enum/flags range | `"[0..3]"` |

---

## Date/Time Formats

| Pattern | Format | Example |
|---------|--------|---------|
| Date only | `DD/MM/YYYY` | `"25/09/2024"` |
| Date 2-digit year | `DD/MM/YY` | `"25/09/24"` |
| Full datetime | `DD/MM/YYYY HH:MM:SS` | `"25/09/2024 08:00:00"` |
| French datetime | `DD/MM/YY HHhMM` | `"25/09/24 08h00"` |
| Day/month only | `DD/MM` | `"25/09"` (year assumed current) |
| Time only | `HHMM` (4 digits) | `"0800"` |

When constructing datetime params to send, always use: `{ "_T": 7, "V": "DD/MM/YYYY HH:MM:SS" }`.

---

## School Year Week Calculation

```
week_number = 1 + floor((target_date - PremierLundi).days / 7)
```

`PremierLundi` comes from `FonctionParametres` → `General.PremierLundi.V` (`DD/MM/YYYY`).

---

## ListeHeures / ListeHeuresFin — Time Slot Lookup

Each item: `{ "G": <slot_number>, "L": "HHhMM" }`.
To find time for slot `place`: find item where `G == place`, parse `L` as `%Hh%M`.

**Lesson end time:**
```
end_place = (lesson["place"] % (len(ListeHeuresFin) - 1)) + lesson["duree"] - 1
end_time  = lookup(ListeHeuresFin, end_place)
```

---

## API Function Details

### PageEmploiDuTemps — Timetable (Onglet 16)

Params:
```json
{
  "ressource":              <user_resource_or_child_resource>,
  "avecAbsencesEleve":      false,
  "avecConseilDeClasse":    true,
  "estEDTPermanence":       false,
  "avecAbsencesRessource":  true,
  "avecDisponibilites":     true,
  "avecInfosPrefsGrille":   true,
  "Ressource":              <user_resource>,
  "NumeroSemaine":          <week_number>,
  "numeroSemaine":          <week_number>
}
```

Note: **both** `NumeroSemaine` (PascalCase) and `numeroSemaine` (camelCase) must be present.

Response: `dataSec.data.ListeCours` — array of lesson objects (see Lesson Fields below).

### PageCahierDeTexte — Homework (Onglet 88)

Params:
```json
{ "domaine": { "_T": 8, "V": "[<weekFrom>..<weekTo>]" } }
```

**Do NOT use `DateDebut`/`DateFin`** — they are silently ignored and return `{}`.
Response: `dataSec.data.ListeTravauxAFaire.V`

### PageCahierDeTexte — Lesson Content (Onglet 89)

Params:
```json
{ "domaine": { "_T": 8, "V": "[<week>..<week>]" } }
```

Response: `dataSec.data.ListeCahierDeTextes.V` — each item has `cours.V.N` (lesson id), `listeContenus.V`.

### DernieresNotes — Grades (Onglet 198)

Params:
```json
{ "Periode": { "N": "<period_id>", "L": "<period_name>" } }
```

Response:
- `dataSec.data.listeDevoirs.V` — grade items
- `dataSec.data.listeServices.V` — subject averages
- `dataSec.data.moyGenerale.V` — overall student average
- `dataSec.data.moyGeneraleClasse.V` — overall class average

### DernieresEvaluations — Competency Evaluations (Onglet 201)

Params:
```json
{ "periode": { "N": "<period_id>", "L": "<period_name>", "G": 2 } }
```

Response: `dataSec.data.listeEvaluations.V`

### PagePresence — Absences / Delays / Punishments (Onglet 19)

Params:
```json
{
  "periode":   { "N": "<period_id>", "L": "<period_name>", "G": 2 },
  "DateDebut": { "_T": 7, "V": "DD/MM/YYYY HH:MM:SS" },
  "DateFin":   { "_T": 7, "V": "DD/MM/YYYY HH:MM:SS" }
}
```

Response: `dataSec.data.listeAbsences.V` — items differentiated by `G`:
- `G == 13` → Absence
- `G == 14` → Delay
- `G == 41` → Punishment

### PageActualites — News / Surveys (Onglet 8)

Params:
```json
{ "modesAffActus": { "_T": 26, "V": "[0..3]" } }
```

Response: `dataSec.data.listeModesAff` — array, each has `listeActualites.V`.

### Navigation — Keep-Alive (Onglet 7)

Params: `{ "onglet": 7, "ongletPrec": 7 }`. Send every ~110 seconds.

### PageBulletins — Report Cards (Onglet 13)

Params:
```json
{ "periode": { "G": 2, "N": "<period_id>", "L": "<period_name>" } }
```

If the response contains a `"Message"` key, no report is available for that period.

### PageEquipePedagogique — Teaching Staff (Onglet 37)

No params. Response: `dataSec.data.liste.V`.

### PageMenus — Canteen Menus (Onglet 10)

Params: `{ "date": { "_T": 7, "V": "DD/MM/YYYY 0:0:0" } }`.
Response: `dataSec.data.ListeJours.V`.

### ListeMessagerie — Discussions (Onglet 131)

Params: `{ "avecMessage": true, "avecLu": true/false }`.
Response: `dataSec.data.listeMessagerie.V`, `dataSec.data.listeEtiquettes.V`.

### ListeMessages — Messages in Discussion (Onglet 131)

Params: `{ "listePossessionsMessages": [{ "N": "<possession_id>" }] }`.

### SaisieTAFFaitEleve — Mark Homework Done (Onglet 88)

Params: `{ "listeTAF": [{ "N": "<homeworkId>", "TAFFait": true/false }] }`.

---

## Response Field Reference

### Lesson Fields (`ListeCours` items)

| Field | Description |
|-------|-------------|
| `N` | Lesson ID |
| `DateDuCours.V` | Start datetime (`DD/MM/YYYY HH:MM:SS`) |
| `DateDuCoursFin.V` | End datetime (may be absent) |
| `place` | Slot position in the week |
| `duree` | Duration in 30-minute slots |
| `estAnnule` | Cancelled lesson |
| `Statut` | Status string (e.g. `"Cours annulé"`) |
| `P` | Sort number |
| `estRetenue` | Detention |
| `memo` | Memo text |
| `CouleurFond` | Background color hex |
| `estSortiePedagogique` | Pedagogical outing |
| `dispenseEleve` | Student exempted |
| `listeVisios.V` | Virtual classroom URLs (each item has `url`) |
| `ListeContenus.V` | Subject, teacher, classroom, group entries |

`ListeContenus.V` items differentiated by `G`:

| `G` | Meaning |
|-----|---------|
| `16` | Subject — `L` = name, `N` = id |
| `3` | Teacher — `L` = name |
| `17` | Classroom — `L` = room name |
| `2` | Group — `L` = group name |

### Homework Fields (`ListeTravauxAFaire.V` items)

| Field | Description |
|-------|-------------|
| `N` | Homework ID |
| `descriptif.V` | HTML description (strip tags for display) |
| `TAFFait` | Done status |
| `Matiere.V` | Subject object (`N`, `L`, `estServiceGroupe`) |
| `PourLe.V` | Due date string |
| `CouleurFond` | Background color |
| `ListePieceJointe.V` | Attachments array |

### Grade Fields (`listeDevoirs.V` items)

| Field | Description |
|-------|-------------|
| `N` | Grade ID |
| `note.V` | Grade value — may be a special code (see below) |
| `bareme.V` | Max points (denominator) |
| `baremeParDefaut.V` | Default max (optional) |
| `date.V` | Date given |
| `service.V` | Subject object |
| `periode.V.N` | Period ID |
| `moyenne.V` | Class average (optional) |
| `noteMax.V` | Highest grade (optional) |
| `noteMin.V` | Lowest grade (optional) |
| `coefficient` | Coefficient |
| `commentaire` | Comment / description |
| `estBonus` | Bonus grade |
| `estFacultatif` | Optional grade |
| `estRamenerSur20` | Normalized to 20 |

Special grade codes — a `|` in the grade string signals a special value:

| `note.V` | Meaning |
|----------|---------|
| `"\|1"` | Absent |
| `"\|2"` | Dispense (exempt) |
| `"\|3"` | NonNote (not graded) |
| `"\|4"` | Inapte |
| `"\|5"` | NonRendu (not submitted) |
| `"\|6"` | AbsentZero |
| `"\|7"` | NonRenduZero |
| `"\|8"` | Felicitations |

### Subject Average Fields (`listeServices.V` items)

| Field | Description |
|-------|-------------|
| `N` / `L` | Subject ID / name |
| `moyEleve.V` | Student average |
| `baremeMoyEleve.V` | Out-of value |
| `moyClasse.V` | Class average |
| `moyMin.V` | Lowest class average |
| `moyMax.V` | Highest class average |
| `couleur` | Subject color |
| `estServiceGroupe` | Group subject |

### Evaluation Fields (`listeEvaluations.V` items)

| Field | Description |
|-------|-------------|
| `L` / `N` | Name / ID |
| `matiere.V` | Subject object |
| `individu.V.L` | Teacher name |
| `coefficient` | Coefficient |
| `descriptif` | Description |
| `date.V` | Date |
| `listePaliers.V` | Paliers (each has `L`) |
| `listeNiveauxDAcquisitions.V` | Acquisition entries (sorted by `ordre`) |

### Acquisition Fields (`listeNiveauxDAcquisitions.V` items)

| Field | Description |
|-------|-------------|
| `N` / `L` | ID / level achieved |
| `abbreviation` | Abbreviated level label |
| `coefficient` | Coefficient |
| `ordre` | Sort order |
| `domaine.V.L` / `.N` | Domain name / ID |
| `item.V.L` / `.N` | Competency item name / ID (optional) |
| `pilier.V.L` / `.N` | Pillar name / ID |
| `pilier.V.strPrefixes` | Pillar prefix string |

### Absence Fields (`listeAbsences.V` where `G == 13`)

| Field | Description |
|-------|-------------|
| `N` | Absence ID |
| `dateDebut.V` / `dateFin.V` | Start / end datetime |
| `justifie` | Is justified |
| `NbrHeures` | Hours missed (string, optional) |
| `NbrJours` | Days missed |
| `listeMotifs.V` | Reasons (each has `L`) |

### Delay Fields (`listeAbsences.V` where `G == 14`)

| Field | Description |
|-------|-------------|
| `N` | Delay ID |
| `date.V` | Datetime of delay |
| `duree` | Minutes late |
| `justifie` | Is justified |
| `justification` | Justification text |
| `listeMotifs.V` | Reasons |

### Punishment Fields (`listeAbsences.V` where `G == 41`)

| Field | Description |
|-------|-------------|
| `N` | Punishment ID |
| `dateDemande.V` | Date given |
| `horsCours` | If false → given during lesson (use `placeDemande` + `ListeHeures`) |
| `placeDemande` | Time slot position |
| `estUneExclusion` | Is an exclusion |
| `travailAFaire` | Homework text (optional) |
| `circonstances` | Circumstances |
| `nature.V.L` | Nature (e.g. `"Retenue"`) |
| `nature.V.estAvecARParent` | Requires parent acknowledgement |
| `listeMotifs.V` | Reasons |
| `demandeur.V.L` | Giver name |
| `duree` | Duration in minutes |
| `estProgrammable` | Has scheduled sessions |
| `programmation.V` | ScheduledPunishment items |

Scheduled punishment items (`programmation.V`):

| Field | Description |
|-------|-------------|
| `N` | ID |
| `date.V` | Date |
| `placeExecution` | Time slot (use `ListeHeures`) |
| `duree` | Duration in minutes |

### Period Fields (`General.ListePeriodes` items)

| Field | Description |
|-------|-------------|
| `N` | Period ID |
| `L` | Period name (e.g. `"Trimestre 1"`) |
| `dateDebut.V` | Start datetime |
| `dateFin.V` | End datetime |

For grade period selection: find the onglet entry with `G == 198` in `listeOngletsPourPeriodes.V`
to identify the default period.

---

## Attachment Handling

### G=0 (Hyperlink)

URL is `attachment["url"]` — or fall back to `attachment["L"]` if `url` is absent.
Stable across sessions. Safe to persist as-is.

### G=1 (Uploaded File)

Must construct a session-scoped authenticated download URL:

1. Build JSON (no spaces): `{"N":"<attachment_N>","Actif":true}`
2. PKCS#7 pad to 16-byte boundary
3. AES-CBC encrypt with current session key/IV
4. Hex-encode ciphertext → `magic_stuff`
5. URL-encode filename (RFC 3986, safe chars: `~()*!.'`)
6. URL = `{root_site}/FichiersExternes/{magic_stuff}/{encoded_filename}?Session={h}`

**The `N` field is NOT stable across sessions** — the token after `#` is regenerated every login.
Use `assignmentId + "|" + fileName` as the stable identifier.

Download the file via a standard authenticated GET on the constructed URL.

---

## User / Resource Descriptor Fields

From `ParametresUtilisateur` → `dataSec.data.ressource`:

| Field | Description |
|-------|-------------|
| `N` | Resource ID |
| `L` | Display name |
| `G` | Type (4 = student/parent resource) |
| `avecPhoto` | Has profile photo |
| `classeDEleve.L` | Class name |
| `Etablissement.V.L` | Establishment name |
| `listeRessources` | (ParentClient only) array of child resource descriptors |
| `listeOngletsPourPeriodes.V` | Onglets for periods (item with `G == 198` = default grade period) |

---

## Error Codes

```json
{ "Erreur": { "G": <code>, "Titre": "<message>" } }
```

| Code | Meaning |
|------|---------|
| 10 | Session expired |
| 22 | Object from previous session (ExpiredObject) |
| 25 | Exceeded max authorization requests |

---

## Key Implementation Notes

1. `PageCahierDeTexte` uses **onglet 88** for homework list and **onglet 89** for lesson content — different onglets, same function name string.
2. `PageEmploiDuTemps` requires **both** `NumeroSemaine` (PascalCase) and `numeroSemaine` (camelCase).
3. `_enleverAlea` keeps only **even-indexed** characters (index 0, 2, 4…) of the decrypted challenge string.
4. `after_auth`: decrypt `cle`, interpret result as comma-separated decimal byte values, convert to `byte[]`, MD5-hash → new session AES key.
5. The `ListeContenus.V` in lesson objects uses `G` values `16/3/17/2` for subject/teacher/classroom/group.
6. Punishment time resolution requires `ListeHeures` (start times), not `ListeHeuresFin`.
7. If `modeCompLog` is true in the Identification response, lowercase the username before key derivation.
