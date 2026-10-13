# HHPhone push через Firebase — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** входящие на HHPBX-учётки доходят до закрытого HHPhone через push Firebase; HHPhone — звонилка по умолчанию, проверенная на S24 Ultra.

**Architecture:** HHPBX хранит FCM-токены номеров (`v_hhphone_push`, `push.php`), перед `local_extension` Lua шлёт push (PHP CLI `push_send.php`, FCM HTTP v1) и ждёт регистрации до 10 с. HHPhone запоминает станцию учётки при обмене QR, отправляет ей токен, а когда все включённые учётки — push и приложение не используется 60 с, выходит тем же путём, что «Выход» (Stop → exitProcess); push запускает его заново.

**Tech Stack:** PHP 8.4 (FusionPBX), Lua (FreeSWITCH), PostgreSQL; Kotlin/Compose, Firebase Messaging (BoM), JUnit 4.

**Spec:** `docs/superpowers/specs/2026-10-13-hhphone-push-design.md` (репозиторий HHPhone).

## Global Constraints

- Версии: HHPhone 0.4.0 (versionCode 6), HHPBX 1.10.0.
- Два репозитория: `G:\HHPBX` (ветка `feat/hhphone-push`, приватный) и `G:\HHPhone` (ветка `feat/push`, публичный — никаких секретов, ключ service account никогда не коммитится).
- Ключ Firebase не кладётся в выпуски HHPBX; только загрузка в панели. Хранение: Debian `/var/lib/hhpbx/push/fcm.json` (www-data, 0600; FreeSWITCH на HHPBX работает от www-data), Windows `%SystemDrive%\ProgramData\fusionpbx\push\fcm.json`.
- Push: data `{"t":"call"}`, `android.priority=HIGH`, `ttl=10s`; ожидание регистрации 10 с, шаг 0,5 с.
- Ответы `push.php`: 200 `{"ok":true}`, 400 `{"error":"format"}`, 403 `{"error":"denied"}`, 405 на не-POST.
- Grace-период HHPhone — 60 с; проверка каждые 15 с.
- Все новые строки — ru/hy/en. Оформление HHDesign (классы `hh-*` в панели).
- Kotlin-пакет `com.tutpro.baresip.plus` не переименовывать; HH-код — в файлах `HH*.kt`, правки upstream-файлов точечные.
- HHPBX: перед сборкой поднять `windows\VERSION`, коммит и пуш (иначе build.ps1 откажет). Новые права/меню панели — только через `core/upgrade/hhpbx_app_rows.php` (здесь не нужны: используется `hhphone_qr`).
- Bash-инструмент схлопывает `\\` в heredoc — файлы с обратным слешем писать инструментом Write.

## Review Focus

1. Звонок на номер без токенов — шаг 885 не добавляет ни задержки, ни побочных эффектов (станции без HHPhone не меняются). Тест: VM-сценарий «нет токенов — без ожидания».
2. Активный SIM-звонок или SIP-звонок — HHPhone не выходит, даже если приложение свёрнуто > 60 с. Тест: `HHPushPolicyTest` (inCall) + проверка на S24.
3. Неверный пароль/чужой домен в `push.php` — 403 без различия причин, токен чужого номера не перезаписать без пароля. Тест: VM-сценарий.
4. Станция без ключа или без интернета — звонок через ≤10 с идёт обычным путём, `push_send.php` не падает. Тест: VM-сценарий «нет ключа».
5. Учётка, которой станция не подтвердила токен (старая HHPBX до 1.10.0, нет сети), — HHPhone держит постоянную связь. Тест: `HHPushTest` (isPush=false без `ok|aor`).

---

## Часть A — HHPBX (`G:\HHPBX`)

### Task 1: Таблица токенов и `push.php`

**Files:**
- Modify: `app/hhphone/app_config.php` (новая таблица после `v_hhphone_tokens`)
- Modify: `app/hhphone/resources/classes/hhphone.php` (статические методы push_*)
- Create: `app/hhphone/push.php`
- Modify: `windows/tests/t_hhphone.php`, `windows/tests/run-all.ps1`
- Create: `linux/tests/vm/hhphone-push-scenarios.sh`

**Interfaces:**
- Produces: `hhphone::valid_fcm_token(string): bool`, `hhphone::push_auth($db, string $user, string $domain, string $pass): ?array` (`extension_uuid`, `domain_uuid`), `hhphone::push_set($db, array $ext, string $token): bool`, `hhphone::push_del($db, array $ext, string $token): bool`, `hhphone::push_tokens($db, string $domain, string $extension): array` (строки токенов), `hhphone::push_drop($db, string $token): void`.

- [ ] **Step 1: Ветка.** `git -C G:/HHPBX switch -c feat/hhphone-push`

- [ ] **Step 2: Тест формата токена (падает).** В конец `windows/tests/t_hhphone.php`:

```php
// push-токены FCM
assert(hhphone::valid_fcm_token('dGVzdA:APA91bH-x_y' . str_repeat('A', 120)), 'fcm token ok');
assert(!hhphone::valid_fcm_token('') && !hhphone::valid_fcm_token('short') && !hhphone::valid_fcm_token(str_repeat('A', 30) . ' x')
	&& !hhphone::valid_fcm_token(str_repeat('A', 4097)), 'fcm token rejects');
```

Run: `php -d zend.assertions=1 -d assert.exception=1 -d extension_dir=C:/tools/php84/ext -d extension=openssl windows/tests/t_hhphone.php`
Expected: FAIL `Call to undefined method hhphone::valid_fcm_token`.

- [ ] **Step 3: Методы класса.** В `hhphone.php` перед `// ---- БД`:

```php
	public static function valid_fcm_token(string $t): bool { return preg_match('/^[A-Za-z0-9_:-]{20,4096}$/', $t) === 1; }
```

В конец класса (после `claim`):

```php
	// ---- push (спека HHPhone 2026-10-13, раздел 2)

	/** Номер по SIP-логину и паролю (push.php), null — не совпало: причину не различаем. */
	public static function push_auth($db, string $user, string $domain, string $pass): ?array {
		if ($pass === '' || preg_match('/^[0-9A-Za-z_.*#+-]{1,32}$/', $user) !== 1) { return null; }
		$e = $db->select("select e.extension_uuid, e.domain_uuid, e.password from v_extensions e join v_domains d using (domain_uuid)
			where e.extension = :u and d.domain_name = :d and e.enabled = 'true'", ['u' => $user, 'd' => $domain], 'row');
		return $e && hash_equals((string)$e['password'], $pass) ? $e : null;
	}

	/** Токен принадлежит одному номеру: set переносит его с прежнего. */
	public static function push_set($db, array $ext, string $token): bool {
		return $db->execute("with gone as (delete from v_hhphone_push where fcm_token = :t)
			insert into v_hhphone_push (hhphone_push_uuid, domain_uuid, extension_uuid, fcm_token, insert_date) values (:u, :d, :e, :t, now())",
			['u' => uuid(), 'd' => $ext['domain_uuid'], 'e' => $ext['extension_uuid'], 't' => $token]) !== false;
	}

	public static function push_del($db, array $ext, string $token): bool {
		return $db->execute("delete from v_hhphone_push where fcm_token = :t and extension_uuid = :e", ['t' => $token, 'e' => $ext['extension_uuid']]) !== false;
	}

	public static function push_tokens($db, string $domain, string $extension): array {
		return array_column((array)$db->select("select p.fcm_token from v_hhphone_push p join v_extensions e using (extension_uuid)
			join v_domains d on d.domain_uuid = e.domain_uuid where e.extension = :x and d.domain_name = :d and e.enabled = 'true'",
			['x' => $extension, 'd' => $domain], 'all'), 'fcm_token');
	}

	public static function push_drop($db, string $token): void {
		$db->execute("delete from v_hhphone_push where fcm_token = :t", ['t' => $token]);
	}
```

Run тест из Step 2. Expected: PASS (без вывода, код 0).

- [ ] **Step 4: Таблица.** В `app/hhphone/app_config.php` после блока `v_hhphone_tokens` — новый блок тем же стилем (`$y++; $z = 0;`): таблица `v_hhphone_push`, поля `hhphone_push_uuid` (uuid, primary), `domain_uuid` (uuid, foreign → v_domains.domain_uuid), `extension_uuid` (uuid), `fcm_token` (text), `insert_date`, `update_date` (timestamptz/date). Типы копировать из соседних полей `v_hhphone_tokens` буквально.

- [ ] **Step 5: `push.php`.**

```php
<?php
// HHPhone: регистрация push-токена номера (без сессии панели; спека HHPhone 2026-10-13, раздел 2)
	$no_session = true;
	require_once dirname(__DIR__, 2) . "/resources/require.php";
	require_once "resources/classes/hhphone.php";
	header('Content-Type: application/json; charset=utf-8');
	header('Cache-Control: no-store');
	if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') { http_response_code(405); header('Allow: POST'); echo '{"error":"method"}'; exit; }
	$op = (string)($_POST['op'] ?? '');
	$fcm = (string)($_POST['token'] ?? '');
	if (!in_array($op, ['set', 'del'], true) || !hhphone::valid_fcm_token($fcm)) { http_response_code(400); echo '{"error":"format"}'; exit; }
	$db = database::new();
	$ext = hhphone::push_auth($db, (string)($_POST['user'] ?? ''), (string)($_POST['domain'] ?? ''), (string)($_POST['pass'] ?? ''));
	if ($ext === null) { usleep(random_int(200000, 400000)); http_response_code(403); echo '{"error":"denied"}'; exit; }   // пауза — против перебора паролей
	$ok = $op === 'set' ? hhphone::push_set($db, $ext, $fcm) : hhphone::push_del($db, $ext, $fcm);
	if (!$ok) { http_response_code(500); echo '{"error":"db"}'; exit; }
	echo '{"ok":true}';
```

- [ ] **Step 6: VM-сценарий (часть push.php).** `linux/tests/vm/hhphone-push-scenarios.sh` — заголовок, `ok/bad`, `dom`, `duuid` скопировать из `hhphone-scenarios.sh` (строки 1–9), далее:

```bash
p101=$(runuser -u postgres -- psql -d fusionpbx -tAc "select password from v_extensions where domain_uuid = '$duuid' and extension = '101'")
p100=$(runuser -u postgres -- psql -d fusionpbx -tAc "select password from v_extensions where domain_uuid = '$duuid' and extension = '100'")
TOK="test:APA91b$(head -c 60 /dev/urandom | base64 | tr -dc A-Za-z0-9 | head -c 80)"
push() { curl -sk -o /tmp/hhpu.json -w '%{http_code}' -X POST "https://127.0.0.1/app/hhphone/push.php" -H "Host: $dom" \
	--data-urlencode "op=$1" --data-urlencode "user=$2" --data-urlencode "domain=$dom" --data-urlencode "pass=$3" --data-urlencode "token=$4"; }
rows() { runuser -u postgres -- psql -d fusionpbx -tAc "select e.extension from v_hhphone_push p join v_extensions e using (extension_uuid) where p.fcm_token = '$1'"; }

c=$(curl -sk -o /dev/null -w '%{http_code}' "https://127.0.0.1/app/hhphone/push.php" -H "Host: $dom"); [ "$c" = 405 ] && ok "GET 405" || bad "GET: $c"
c=$(push set 101 "$p101" short); [ "$c" = 400 ] && ok "bad token 400" || bad "bad token: $c"
c=$(push set 101 wrong "$TOK"); [ "$c" = 403 ] && [ -z "$(rows "$TOK")" ] && ok "wrong pass 403" || bad "wrong pass: $c"
c=$(push set 101 "$p101" "$TOK"); [ "$c" = 200 ] && [ "$(rows "$TOK")" = 101 ] && ok "set" || bad "set: $c $(cat /tmp/hhpu.json)"
c=$(push set 101 "$p101" "$TOK"); n=$(runuser -u postgres -- psql -d fusionpbx -tAc "select count(*) from v_hhphone_push where fcm_token = '$TOK'")
[ "$c" = 200 ] && [ "$n" = 1 ] && ok "set twice → one row" || bad "set twice: $n"
c=$(push set 100 "$p100" "$TOK"); [ "$(rows "$TOK")" = 100 ] && ok "token moves to 100" || bad "move: $(rows "$TOK")"
c=$(push del 101 "$p101" "$TOK"); [ "$(rows "$TOK")" = 100 ] && ok "del by other ext ignored" || bad "foreign del"
c=$(push del 100 "$p100" "$TOK"); [ "$c" = 200 ] && [ -z "$(rows "$TOK")" ] && ok "del" || bad "del: $c"
```

(в конце файла — `exit $fail`; Task 2–3 дописывают сценарии перед ним).

- [ ] **Step 7: run-all.** В `windows/tests/run-all.ps1` в список тестов добавить `'t_hhphone'`.

- [ ] **Step 8: Коммит.**

```bash
git -C G:/HHPBX add app/hhphone windows/tests linux/tests/vm/hhphone-push-scenarios.sh
git -C G:/HHPBX commit -m "HHPhone push: таблица токенов и push.php"
```

(VM-сценарий запускается в Task 5 после развёртывания.)

### Task 2: Отправка через FCM HTTP v1

**Files:**
- Create: `app/hhphone/resources/classes/hhphone_fcm.php`
- Create: `app/hhphone/push_send.php`
- Create: `windows/tests/t_hhphone_fcm.php`; Modify: `windows/tests/run-all.ps1`

**Interfaces:**
- Consumes: `hhphone::push_tokens`, `hhphone::push_drop` (Task 1).
- Produces: `hhphone_fcm::dir(): string`, `new hhphone_fcm(string $dir = '')`, `->key(): ?array`, `hhphone_fcm::valid_key($k): bool`, `->save_key(string $json): bool`, `->delete_key(): bool`, `hhphone_fcm::jwt(array $key, int $now): string`, `->access_token(bool $fresh = false): ?string`, `->send(string $token): string` (`ok|gone|error`); защищённый `post(string $url, array $headers, string $body): array` `[int код, string тело]` — подменяется в тестах.

- [ ] **Step 1: Тест (падает).** `windows/tests/t_hhphone_fcm.php`:

```php
<?php
// self-check: HHPhone push — php -d zend.assertions=1 -d assert.exception=1 -d extension_dir=C:/tools/php84/ext -d extension=openssl windows/tests/t_hhphone_fcm.php
require dirname(__DIR__, 2) . '/app/hhphone/resources/classes/hhphone_fcm.php';

$pk = openssl_pkey_new(['private_key_bits' => 2048, 'private_key_type' => OPENSSL_KEYTYPE_RSA]);
openssl_pkey_export($pk, $pem);
$key = ['type' => 'service_account', 'project_id' => 'hhphone-test', 'client_email' => 'push@hhphone-test.iam.gserviceaccount.com', 'private_key' => $pem];
assert(hhphone_fcm::valid_key($key) && !hhphone_fcm::valid_key(['type' => 'x']) && !hhphone_fcm::valid_key(null), 'valid_key');

// JWT RS256 проверяется открытым ключом
[$h, $c, $s] = explode('.', hhphone_fcm::jwt($key, 1000));
$d = fn($x) => base64_decode(strtr($x, '-_', '+/'));
assert(json_decode($d($h), true) === ['alg' => 'RS256', 'typ' => 'JWT'], 'jwt header');
$claims = json_decode($d($c), true);
assert($claims['iss'] === $key['client_email'] && $claims['iat'] === 1000 && $claims['exp'] === 4600
	&& $claims['aud'] === 'https://oauth2.googleapis.com/token' && $claims['scope'] === 'https://www.googleapis.com/auth/firebase.messaging', 'jwt claims');
assert(openssl_verify("$h.$c", $d($s), openssl_pkey_get_details($pk)['key'], OPENSSL_ALGO_SHA256) === 1, 'jwt signature');

// подменённый HTTP
class fake_fcm extends hhphone_fcm {
	public array $calls = []; public array $answers = [];
	protected function post(string $url, array $headers, string $body): array { $this->calls[] = [$url, $body]; return array_shift($this->answers); }
}
$dir = sys_get_temp_dir() . '/hhfcm' . bin2hex(random_bytes(4));
$f = new fake_fcm($dir);
assert($f->key() === null && $f->send('tok') === 'error' && $f->calls === [], 'no key → error, no HTTP');
assert(!$f->save_key('{"type":"x"}') && $f->save_key(json_encode($key)) && $f->key()['project_id'] === 'hhphone-test', 'save_key');

$f->answers = [[200, '{"access_token":"AT1","expires_in":3599}'], [200, '{}'], [200, '{}']];
assert($f->send('tok') === 'ok' && $f->send('tok2') === 'ok', 'send ok');
assert(count($f->calls) === 3, 'access token cached between sends');
assert($f->calls[1][0] === 'https://fcm.googleapis.com/v1/projects/hhphone-test/messages:send', 'send url');
$m = json_decode($f->calls[1][1], true)['message'];
assert($m['token'] === 'tok' && $m['data'] === ['t' => 'call'] && $m['android'] === ['priority' => 'HIGH', 'ttl' => '10s'], 'payload');

$g = new fake_fcm($dir);   // кэш токена доступа — в файле, общий между процессами
$g->answers = [[404, '{"error":{"status":"NOT_FOUND","details":[{"errorCode":"UNREGISTERED"}]}}'], [500, 'oops']];
assert($g->send('old') === 'gone' && $g->send('x') === 'error' && count($g->calls) === 2, 'gone / error, cache reused');

$g->answers = [[401, 'bad']];
assert($g->access_token(true) === null, 'fresh token failure');
assert($f->delete_key() && $f->key() === null, 'delete_key');
array_map('unlink', glob("$dir/*")); rmdir($dir);
```

Run: `php -d zend.assertions=1 -d assert.exception=1 -d extension_dir=C:/tools/php84/ext -d extension=openssl windows/tests/t_hhphone_fcm.php`
Expected: FAIL (нет файла класса).

- [ ] **Step 2: Класс.** `app/hhphone/resources/classes/hhphone_fcm.php`:

```php
<?php
// HHPhone: push через FCM HTTP v1 (спека HHPhone 2026-10-13, раздел 2). Ключ — только на станции, вне веб-корня.
class hhphone_fcm {
	public function __construct(protected string $dir = '') { if ($this->dir === '') { $this->dir = self::dir(); } }

	public static function dir(): string {
		return PHP_OS_FAMILY === 'Windows' ? (getenv('SystemDrive') ?: 'C:') . '\\ProgramData\\fusionpbx\\push' : '/var/lib/hhpbx/push';
	}

	public static function valid_key($k): bool {
		return is_array($k) && ($k['type'] ?? '') === 'service_account' && !empty($k['project_id']) && !empty($k['client_email'])
			&& str_contains((string)($k['private_key'] ?? ''), 'PRIVATE KEY');
	}

	public function key(): ?array {
		$k = json_decode((string)@file_get_contents($this->dir . '/fcm.json'), true);
		return self::valid_key($k) ? $k : null;
	}

	public function save_key(string $json): bool {
		if (!self::valid_key(json_decode($json, true))) { return false; }
		if (!is_dir($this->dir) && !@mkdir($this->dir, 0700, true)) { return false; }
		@unlink($this->dir . '/token.json');
		$ok = file_put_contents($this->dir . '/fcm.json', $json, LOCK_EX) !== false;
		@chmod($this->dir . '/fcm.json', 0600);
		return $ok;
	}

	public function delete_key(): bool {
		@unlink($this->dir . '/token.json');
		return !file_exists($this->dir . '/fcm.json') || @unlink($this->dir . '/fcm.json');
	}

	public static function b64url(string $bin): string { return rtrim(strtr(base64_encode($bin), '+/', '-_'), '='); }

	public static function jwt(array $key, int $now): string {
		$h = self::b64url(json_encode(['alg' => 'RS256', 'typ' => 'JWT']));
		$c = self::b64url(json_encode(['iss' => $key['client_email'], 'scope' => 'https://www.googleapis.com/auth/firebase.messaging',
			'aud' => 'https://oauth2.googleapis.com/token', 'iat' => $now, 'exp' => $now + 3600], JSON_UNESCAPED_SLASHES));
		openssl_sign("$h.$c", $sig, $key['private_key'], OPENSSL_ALGO_SHA256);
		return "$h.$c." . self::b64url($sig);
	}

	/** [код, тело]; 0 — нет связи. */
	protected function post(string $url, array $headers, string $body): array {
		$ch = curl_init($url);
		curl_setopt_array($ch, [CURLOPT_POST => true, CURLOPT_POSTFIELDS => $body, CURLOPT_HTTPHEADER => $headers,
			CURLOPT_RETURNTRANSFER => true, CURLOPT_CONNECTTIMEOUT => 5, CURLOPT_TIMEOUT => 5]);
		$out = curl_exec($ch);
		$code = (int)curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
		curl_close($ch);
		return [$out === false ? 0 : $code, (string)$out];
	}

	/** Токен доступа OAuth2; кэш в token.json до истечения минус 5 минут. */
	public function access_token(bool $fresh = false): ?string {
		$key = $this->key();
		if ($key === null) { return null; }
		$cache = json_decode((string)@file_get_contents($this->dir . '/token.json'), true);
		if (!$fresh && is_array($cache) && ($cache['exp'] ?? 0) > time() + 300) { return (string)$cache['token']; }
		[$code, $body] = $this->post('https://oauth2.googleapis.com/token', ['Content-Type: application/x-www-form-urlencoded'],
			http_build_query(['grant_type' => 'urn:ietf:params:oauth:grant-type:jwt-bearer', 'assertion' => self::jwt($key, time())]));
		$r = json_decode($body, true);
		if ($code !== 200 || empty($r['access_token'])) { return null; }
		@file_put_contents($this->dir . '/token.json', json_encode(['token' => $r['access_token'], 'exp' => time() + (int)($r['expires_in'] ?? 3600)]), LOCK_EX);
		@chmod($this->dir . '/token.json', 0600);
		return (string)$r['access_token'];
	}

	/** ok | gone (токен телефона недействителен — удалить) | error. */
	public function send(string $token): string {
		$key = $this->key();
		$at = $key ? $this->access_token() : null;
		if ($at === null) { return 'error'; }
		[$code, $body] = $this->post("https://fcm.googleapis.com/v1/projects/{$key['project_id']}/messages:send",
			["Authorization: Bearer $at", 'Content-Type: application/json'],
			json_encode(['message' => ['token' => $token, 'data' => ['t' => 'call'], 'android' => ['priority' => 'HIGH', 'ttl' => '10s']]]));
		if ($code === 200) { return 'ok'; }
		return $code === 404 || str_contains($body, 'UNREGISTERED') ? 'gone' : 'error';
	}
}
```

Run тест. Expected: PASS.

- [ ] **Step 3: CLI.** `app/hhphone/push_send.php`:

```php
<?php
// HHPhone: php push_send.php <domain> <extension> — push всем телефонам номера; запускает app/hhphone_push/index.lua фоном
	if (PHP_SAPI !== 'cli' || $argc !== 3) { exit(2); }
	$no_session = true;
	chdir(dirname(__DIR__, 2));
	require_once "resources/require.php";
	require_once "app/hhphone/resources/classes/hhphone.php";
	require_once "app/hhphone/resources/classes/hhphone_fcm.php";
	$db = database::new();
	$fcm = new hhphone_fcm;
	foreach (hhphone::push_tokens($db, $argv[1], $argv[2]) as $t) {
		if ($fcm->send($t) === 'gone') { hhphone::push_drop($db, $t); }
	}
```

- [ ] **Step 4: run-all** — добавить `'t_hhphone_fcm'`. Коммит:

```bash
git -C G:/HHPBX add app/hhphone windows/tests
git -C G:/HHPBX commit -m "HHPhone push: отправка через FCM HTTP v1"
```

### Task 3: Шаг диалплана 885 и Lua

**Files:**
- Create: `app/dialplans/resources/switch/conf/dialplan/885_hhphone_push.xml`
- Create: `app/switch/resources/scripts/app/hhphone_push/index.lua`
- Modify: `linux/tests/vm/hhphone-push-scenarios.sh`

**Interfaces:**
- Consumes: `app/hhphone/push_send.php <domain> <extension>` (Task 2); глобальные `php_dir`, `php_bin`, `document_root` из `resources.functions.config` (их загружает `app.lua`).

- [ ] **Step 1: VM-сценарий (падает до развёртывания).** Дописать перед `exit $fail`:

```bash
# диалплан 885 есть в домене
n=$(runuser -u postgres -- psql -d fusionpbx -tAc "select count(*) from v_dialplans where app_uuid = '4d2e7c1a-9b3f-4e6a-8c51-2f7d0b9e6a14' and domain_uuid = '$duuid'")
[ "$n" = 1 ] && ok "dialplan 885 in domain" || bad "dialplan 885: $n"
# 101 не зарегистрирован, токенов нет → без ожидания; с токеном → ждёт ~10 с (ключа нет — push не уходит, звонок идёт дальше)
callt() { local s=$(date +%s); fs_cli -x "originate {origination_caller_id_number=100,originate_timeout=30}loopback/101/$dom &park()" >/dev/null; echo $(( $(date +%s) - s )); }
fs_cli -x "sofia profile internal flush_inbound_reg 101@$dom" >/dev/null
t0=$(callt); [ "$t0" -lt 5 ] && ok "no tokens: no wait (${t0}s)" || bad "no tokens waited ${t0}s"
push set 101 "$p101" "$TOK" >/dev/null
t1=$(callt); [ "$t1" -ge 9 ] && [ "$t1" -le 16 ] && ok "token: waits ~10s (${t1}s)" || bad "token wait ${t1}s"
grep -q "\[hhphone_push\] 101@$dom timeout" /var/log/freeswitch/freeswitch.log && ok "lua logged timeout" || bad "lua log"
push del 101 "$p101" "$TOK" >/dev/null
```

(точное время звонка без ответа зависит от `originate_timeout`/голосовой почты; критерий — разница «без токена» < 5 с и «с токеном» ≥ 9 с. Если на стенде 101 зарегистрирован эмулятором — сценарий запускать при выключенном эмуляторе.)

- [ ] **Step 2: Диалплан.**

```xml
<extension name="hhphone_push" number="[ext]" context="global" continue="true" app_uuid="4d2e7c1a-9b3f-4e6a-8c51-2f7d0b9e6a14" global="true" order="885">
	<condition field="${user_exists}" expression="^true$">
		<action application="lua" data="app.lua hhphone_push"/>
	</condition>
</extension>
```

- [ ] **Step 3: Lua.** `app/switch/resources/scripts/app/hhphone_push/index.lua`:

```lua
-- HHPhone: push телефонам номера и ожидание регистрации до 10 с (спека HHPhone 2026-10-13, раздел 1).
-- Нет токенов — выходим сразу: станции без HHPhone не меняются.
	local Database = require "resources.functions.database"
	local domain_name = session:getVariable("domain_name") or ""
	local ext = session:getVariable("destination_number") or ""
	local dbh = Database.new('system')
	local n = dbh:first_value([[select count(*) from v_hhphone_push p join v_extensions e on e.extension_uuid = p.extension_uuid
		join v_domains d on d.domain_uuid = e.domain_uuid where e.extension = :ext and d.domain_name = :dom and e.enabled = 'true']],
		{ext = ext, dom = domain_name})
	dbh:release()
	if (tonumber(n) or 0) == 0 then return end

	local api = freeswitch.API()
	local q = function(s) return '"' .. s .. '"' end
	api:executeString("bg_system " .. q(php_dir .. "/" .. php_bin) .. " " .. q(document_root .. "/app/hhphone/push_send.php") .. " " .. q(domain_name) .. " " .. q(ext))
	local function registered() return not api:executeString("sofia_contact */" .. ext .. "@" .. domain_name):find("error/") end
	if registered() then return end   -- приложение на связи: push ушёл на всякий случай, без ожидания

	session:execute("ring_ready")
	for i = 1, 20 do
		session:sleep(500)
		if not session:ready() then return end
		if registered() then
			freeswitch.consoleLog("notice", "[hhphone_push] " .. ext .. "@" .. domain_name .. " registered after " .. (i * 500) .. " ms\n")
			session:sleep(300)
			return
		end
	end
	freeswitch.consoleLog("notice", "[hhphone_push] " .. ext .. "@" .. domain_name .. " timeout\n")
```

Проверить: `grep -n "php.bin" app/switch/resources/scripts/resources/functions/config.lua` → глобальная называется `php_bin` (рядом с `php_dir`, строка ~95). Если имя другое — исправить в Lua и записать Ruling.

- [ ] **Step 4: Коммит.**

```bash
git -C G:/HHPBX add app/dialplans/resources/switch/conf/dialplan/885_hhphone_push.xml app/switch/resources/scripts/app/hhphone_push linux/tests/vm/hhphone-push-scenarios.sh
git -C G:/HHPBX commit -m "HHPhone push: шаг диалплана 885 — push и ожидание регистрации"
```

### Task 4: Ключ Firebase в панели

**Files:**
- Modify: `app/hhphone/action.php` (ops `key_upload`, `key_test`, `key_delete`)
- Modify: `app/hhphone/index.php` (карточка «Push-уведомления»)
- Modify: `app/hhphone/app_languages.php` (ru/en/hy)
- Modify: `linux/pkg/hhpbx/DEBIAN/postinst` (каталог ключа)
- Modify: `linux/tests/vm/hhphone-push-scenarios.sh`

- [ ] **Step 1: VM-сценарий (падает).** Перед `exit $fail`:

```bash
d=$(stat -c '%U %a' /var/lib/hhpbx/push 2>/dev/null); [ "$d" = "www-data 700" ] && ok "key dir" || bad "key dir: $d"
runuser -u www-data -- php -r "chdir('$W'); require 'app/hhphone/resources/classes/hhphone_fcm.php';
	\$k = openssl_pkey_new(['private_key_bits' => 2048]); openssl_pkey_export(\$k, \$p);
	exit((new hhphone_fcm)->save_key(json_encode(['type'=>'service_account','project_id'=>'t','client_email'=>'a@b','private_key'=>\$p])) ? 0 : 1);" \
	&& [ "$(stat -c '%a' /var/lib/hhpbx/push/fcm.json)" = 600 ] && ok "save key 0600" || bad "save key"
runuser -u www-data -- php "$W/app/hhphone/push_send.php" "$dom" 101 && ok "push_send with fake key exits 0" || bad "push_send crashed"
rm -f /var/lib/hhpbx/push/fcm.json /var/lib/hhpbx/push/token.json
```

- [ ] **Step 2: postinst.** Рядом со строкой 25 (`install -d ... /var/cache/fusionpbx ...`) добавить:

```sh
install -d -m 700 -o www-data -g www-data /var/lib/hhpbx/push   # ключ Firebase для push HHPhone (загружается в панели)
```

- [ ] **Step 3: action.php.** В `switch`/диспетчер `op` добавить (перед веткой «иначе 400»), подключив `require_once "resources/classes/hhphone_fcm.php";`:

```php
	if ($op === 'key_upload') {
		$json = is_uploaded_file($_FILES['key']['tmp_name'] ?? '') ? (string)file_get_contents($_FILES['key']['tmp_name']) : '';
		echo json_encode((new hhphone_fcm)->save_key($json) ? ['ok' => true] : ['ok' => false, 'message' => 'key_bad']); exit;
	}
	if ($op === 'key_test') { echo json_encode((new hhphone_fcm)->access_token(true) !== null ? ['ok' => true] : ['ok' => false, 'message' => 'key_fail']); exit; }
	if ($op === 'key_delete') { echo json_encode(['ok' => (new hhphone_fcm)->delete_key()]); exit; }
```

(форма action.php — по фактическому коду: если там `switch`, оформить как `case`; ответы — тем же JSON, что у `pin/create/revoke`).

- [ ] **Step 4: index.php.** Новая `<section class='hh-card'>` после карточки PIN:
  - заголовок `h3.hh-h2` «Push-уведомления»; `p.hh-muted` с пояснением;
  - если `(new hhphone_fcm)->key()` есть — «Ключ загружен, проект: <project_id>» и кнопки `hh-btn` «Проверить» (`op=key_test`, при успехе `alert(msg.key_ok)`), `hh-btn danger` «Удалить» (`op=key_delete`, `location.reload()`);
  - иначе — предупреждение «Ключ не загружен: телефоны получают звонки, только пока HHPhone открыт» и `<input type='file' accept='.json' class='hh-field'>` + `hh-btn primary` «Загрузить» (FormData с полем `key` = файл, `op=key_upload`, `location.reload()`).
  - `post()` расширить: если значение — `File`, `f.append(k, v)` уже работает (FormData принимает File) — проверить в браузере.
  - Все тексты — через `$L('push-…')` и `msg`.

- [ ] **Step 5: Переводы** в `app_languages.php` тем же форматом, что `label-hhphone-*`:

| ключ | ru | en | hy |
|---|---|---|---|
| push-title | Push-уведомления | Push notifications | Push ծանուցումներ |
| push-help | HHPhone на номерах этой АТС получает звонки, даже когда закрыт. Нужен ключ Firebase (JSON сервисного аккаунта). | HHPhone on this PBX's extensions receives calls even when closed. Requires a Firebase key (service account JSON). | Այս ԱՀԿ-ի համարների HHPhone-ը զանգեր է ստանում նույնիսկ փակ վիճակում։ Անհրաժեշտ է Firebase բանալի (ծառայողական հաշվի JSON)։ |
| push-loaded | Ключ загружен, проект: | Key loaded, project: | Բանալին բեռնված է, նախագիծ՝ |
| push-none | Ключ не загружен: телефоны получают звонки, только пока HHPhone открыт. | No key: phones receive calls only while HHPhone is open. | Բանալի չկա․ հեռախոսները զանգեր ստանում են միայն HHPhone-ը բաց լինելիս։ |
| push-upload | Загрузить | Upload | Բեռնել |
| push-test | Проверить | Check | Ստուգել |
| push-delete | Удалить | Delete | Ջնջել |
| key_bad | Это не ключ сервисного аккаунта Firebase. | This is not a Firebase service account key. | Սա Firebase ծառայողական հաշվի բանալի չէ։ |
| key_fail | Google не принял ключ или нет связи с интернетом. | Google rejected the key or there is no internet. | Google-ը չընդունեց բանալին կամ ինտերնետ չկա։ |
| key_ok | Ключ работает. | The key works. | Բանալին աշխատում է։ |

- [ ] **Step 6: Коммит.**

```bash
git -C G:/HHPBX add app/hhphone linux/pkg/hhpbx/DEBIAN/postinst linux/tests/vm/hhphone-push-scenarios.sh
git -C G:/HHPBX commit -m "HHPhone push: ключ Firebase в панели"
```

### Task 5: Развёртывание на стенде и прогон

- [ ] **Step 1:** Включить hhpbx-test (vmrun, 192.168.109.132; ВМ — минимум RAM), скопировать изменённые файлы `app/hhphone`, `app/dialplans/.../885_hhphone_push.xml`, `app/switch/resources/scripts/app/hhphone_push` в `/var/www/fusionpbx/…` (scp hayk@, затем sudo cp), `install -d -m 700 -o www-data -g www-data /var/lib/hhpbx/push`, затем `cd /var/www/fusionpbx && php core/upgrade/upgrade_schema.php && php core/upgrade/upgrade.php && rm -rf /var/cache/fusionpbx/* && systemctl restart freeswitch`.
- [ ] **Step 2:** `sudo bash hhphone-push-scenarios.sh` и `sudo bash hhphone-scenarios.sh`. Expected: все `OK`, код 0.
- [ ] **Step 3:** Панель в браузере (Chrome): карточка «Push-уведомления» в светлой и тёмной теме, загрузка ключа пользователя (`C:\Users\haykh\.hhphone\fcm-key.json`, если уже положен) → «Проверить» → «Ключ работает».
- [ ] **Step 4:** Windows-тесты `t_hhphone`, `t_hhphone_fcm` локально (команды из Task 1–2). Коммит фиксов, если были.

---

## Часть B — HHPhone (`G:\HHPhone`)

### Task 6: Firebase в сборке

**Files:**
- Modify: `gradle/libs.versions.toml`, `build.gradle.kts` (корень), `app/build.gradle.kts`
- Create: `app/src/hh/google-services.json` (файл пользователя из `G:\HHPhone\google-services.json`, перенести)
- Create: `app/src/main/kotlin/com/tutpro/baresip/plus/HHPushService.kt` (каркас)
- Modify: `app/src/hh/AndroidManifest.xml`

- [ ] **Step 1: Ветка.** `git -C G:/HHPhone switch -c feat/push`
- [ ] **Step 2: Каталог.** В `libs.versions.toml`:

```toml
[versions]
googleServices = "4.4.4"
firebaseBom = "34.4.0"
[libraries]
google-services = { group = "com.google.gms", name = "google-services", version.ref = "googleServices" }
firebase-bom = { group = "com.google.firebase", name = "firebase-bom", version.ref = "firebaseBom" }
firebase-messaging = { group = "com.google.firebase", name = "firebase-messaging" }
```

(версии — последние стабильные на момент сборки; если gradle не находит — взять из https://firebase.google.com/support/release-notes/android и записать Ruling).

Корень `build.gradle.kts`, в `dependencies` buildscript: `classpath(libs.google.services)`.
`app/build.gradle.kts`: `apply(plugin = "com.google.gms.google-services")` после блока plugins; в dependencies `implementation(platform(libs.firebase.bom))`, `implementation(libs.firebase.messaging)`.
Ruling (заранее): `implementation`, а не `hhImplementation` — flavor `hh` единственный, результат тот же, а upstream-слияния не затрагивают этих строк.

- [ ] **Step 3: google-services.json.** `git mv` невозможен (файл не в git): `mv G:/HHPhone/google-services.json G:/HHPhone/app/src/hh/google-services.json`. Проверить, что `package_name` внутри = `am.dgsolutions.hhphone`. Файл коммитится (секретов нет).

- [ ] **Step 4: Каркас сервиса.**

```kotlin
package com.tutpro.baresip.plus

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** Push от HHPBX (спека 2026-10-13): будит HHPhone к входящему звонку, держит FCM-токен станций актуальным. */
class HHPushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {}
    override fun onMessageReceived(message: RemoteMessage) {}
}
```

Манифест `app/src/hh/AndroidManifest.xml`, внутри `<application>`:

```xml
<service android:name="com.tutpro.baresip.plus.HHPushService" android:exported="false">
    <intent-filter><action android:name="com.google.firebase.MESSAGING_EVENT" /></intent-filter>
</service>
```

- [ ] **Step 4a:** Если в `proguard-rules.pro` нет правил для Firebase — они не нужны (библиотека несёт свои consumer rules); проверка — сборка release в Task 10.
- [ ] **Step 5: Сборка.** PowerShell: `cd G:\HHPhone; .\gradlew.bat assembleHhDebug testHhDebugUnitTest`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 6: Коммит.** `git add gradle app/build.gradle.kts build.gradle.kts app/src/hh app/src/main/kotlin/com/tutpro/baresip/plus/HHPushService.kt && git commit -m "Firebase Messaging в сборке HHPhone"`

### Task 7: Станция учётки и синхронизация токена

**Files:**
- Modify: `HHClaim.kt` (вынести `open`)
- Create: `HHPush.kt`
- Create: `app/src/test/kotlin/com/tutpro/baresip/plus/HHPushTest.kt`
- Modify: `HHHost.kt` (`claim`), `AccountsScreen.kt` (удаление), `BaresipApp.kt` (init), `BaresipService.kt` (sync после готовности), `HHPushService.kt` (`onNewToken`)

(пути Kotlin — `app/src/main/kotlin/com/tutpro/baresip/plus/`)

**Interfaces:**
- Produces: `HHClaim.open(server: String, spki: String, path: String): HttpsURLConnection`; `object HHPush { fun init(ctx: Context); var token: String?; fun remember(aor: String, server: String, spki: String); fun isPush(aor: String): Boolean; fun sync(); fun forget(aor: String); fun touch(); fun idleMs(): Long; internal fun userDomain(aor: String): Pair<String, String>; internal fun form(vararg kv: Pair<String, String>): String }`.

- [ ] **Step 1: Тест (падает).**

```kotlin
package com.tutpro.baresip.plus

import org.junit.Assert.assertEquals
import org.junit.Test

class HHPushTest {
    @Test fun userDomain() {
        assertEquals("101" to "pbx.orbeli.am", HHPush.userDomain("sip:101@pbx.orbeli.am"))
        assertEquals("101" to "pbx.orbeli.am", HHPush.userDomain("sip:101@pbx.orbeli.am;transport=tcp"))
    }
    @Test fun formEncodes() {
        assertEquals("op=set&pass=a%26b%3Dc&token=x%3Ay", HHPush.form("op" to "set", "pass" to "a&b=c", "token" to "x:y"))
    }
}
```

`isPush` зависит от SharedPreferences — его покрывает проверка на устройстве (Task 10) и Review Focus 5.

Run: `.\gradlew.bat testHhDebugUnitTest --tests "*HHPushTest*"` Expected: FAIL (нет HHPush).

- [ ] **Step 2: `HHClaim.open`.** Строки `exchange()` от `val system = …` до `setRequestProperty(...)` вынести:

```kotlin
    /** HTTPS к HHPBX с ключом, закреплённым QR: claim.php и push.php. */
    fun open(server: String, spki: String, path: String): HttpsURLConnection {
        val system = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }.trustManagers.filterIsInstance<X509TrustManager>().first()
        val trust = HHPinningTrust(spki, system)
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        return (URL("$server$path").openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = ssl.socketFactory
            val standard = HttpsURLConnection.getDefaultHostnameVerifier()
            hostnameVerifier = javax.net.ssl.HostnameVerifier { host, session -> trust.pinned || standard.verify(host, session) }
            requestMethod = "POST"; connectTimeout = 10_000; readTimeout = 10_000; doOutput = true; instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
    }
```

`exchange` начинается с `val conn = open(c.server, c.spki, "/app/hhphone/claim.php")`. Существующие `HHClaimTest` должны остаться зелёными.

- [ ] **Step 3: `HHPush.kt`.**

```kotlin
package com.tutpro.baresip.plus

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import java.net.URLEncoder

/**
 * Push через HHPBX (спека 2026-10-13). Учётка — push, если станция приняла текущий FCM-токен;
 * остальные («чужие», старые, без связи) держат постоянную связь.
 * Ключи "hh_push": "s|<aor>" = "server\nspki", "ok|<aor>" = токен, принятый станцией, "token" = текущий FCM-токен.
 */
object HHPush {
    private const val TAG = "HHPush"
    private lateinit var prefs: SharedPreferences
    @Volatile private var lastActive = SystemClock.elapsedRealtime()

    fun init(ctx: Context) { prefs = ctx.getSharedPreferences("hh_push", Context.MODE_PRIVATE) }

    var token: String?
        get() = prefs.getString("token", null)
        set(value) = prefs.edit { putString("token", value) }

    fun remember(aor: String, server: String, spki: String) =
        prefs.edit { putString("s|$aor", "$server\n$spki"); remove("ok|$aor") }

    private fun station(aor: String): Pair<String, String>? =
        prefs.getString("s|$aor", null)?.split("\n")?.takeIf { it.size == 2 }?.let { it[0] to it[1] }

    fun isPush(aor: String): Boolean = token.let { it != null && prefs.getString("ok|$aor", null) == it }

    fun touch() { lastActive = SystemClock.elapsedRealtime() }
    fun idleMs(): Long = SystemClock.elapsedRealtime() - lastActive

    /** Отправить токен станциям, которые его ещё не приняли. Сеть — вызывать не из UI-потока. */
    @Synchronized fun sync() {
        val t = token ?: return
        for (key in prefs.all.keys.filter { it.startsWith("s|") }) {
            val aor = key.removePrefix("s|")
            if (prefs.getString("ok|$aor", null) == t) continue
            val (server, spki) = station(aor) ?: continue
            val acc = Account.ofAor(aor) ?: continue
            val (user, domain) = userDomain(aor)
            if (post(server, spki, form("op" to "set", "user" to user, "domain" to domain, "pass" to acc.authPass, "token" to t)))
                prefs.edit { putString("ok|$aor", t) }
        }
    }

    /** Учётка удаляется: снять токен со станции (до удаления — нужен пароль), забыть станцию. */
    fun forget(aor: String) {
        val st = station(aor)
        val pass = Account.ofAor(aor)?.authPass
        val t = token
        prefs.edit { remove("s|$aor"); remove("ok|$aor") }
        if (st == null || pass == null || t == null) return
        val (user, domain) = userDomain(aor)
        Thread { post(st.first, st.second, form("op" to "del", "user" to user, "domain" to domain, "pass" to pass, "token" to t)) }.start()
    }

    internal fun userDomain(aor: String): Pair<String, String> =
        aor.removePrefix("sip:").substringBefore(";").let { it.substringBefore("@") to it.substringAfter("@") }

    internal fun form(vararg kv: Pair<String, String>): String =
        kv.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

    private fun post(server: String, spki: String, body: String): Boolean = try {
        val c = HHClaim.open(server, spki, "/app/hhphone/push.php")
        try { c.outputStream.use { it.write(body.toByteArray()) }; c.responseCode == 200 } finally { c.disconnect() }
    } catch (e: Exception) { Log.w(TAG, "push.php: ${e.message}"); false }
}
```

Run тест Step 1. Expected: PASS.

- [ ] **Step 4: Подключение.**
  - `BaresipApp.onCreate`: `HHPush.init(this)` рядом с `HHStaff.init(...)`.
  - `HHHost.claim`: в ветке `HHClaimOutcome.Ok` передать станцию — в `addAccount` после `Account.saveAccounts()`:
    ```kotlin
    station?.let { (server, spki) -> HHPush.remember(ua.account.aor, server, spki); Thread { HHPush.sync() }.start() }
    ```
    где `station: Pair<String, String>?` — новый параметр `addAccount`/`replaceIfNeeded` (из `claim` — `c.server to c.spki`, из QR с паролем — `null`). В `replaceIfNeeded` перед `existing.remove()`: `HHPush.forget(aor)`.
  - `AccountsScreen.kt` (удаление, ~строка 148): первой строкой блока `HHPush.forget(aor)`.
  - `BaresipService.kt` после `isNativeReady = true` (строка ~2041):
    ```kotlin
    com.google.firebase.messaging.FirebaseMessaging.getInstance().token.addOnSuccessListener { t ->
        if (t != HHPush.token) HHPush.token = t
        Thread { HHPush.sync() }.start()
    }
    ```
  - `HHPushService.onNewToken`: `HHPush.token = token; if (BaresipService.isNativeReady) HHPush.sync()` (onNewToken уже в фоновом потоке).

- [ ] **Step 5:** `.\gradlew.bat testHhDebugUnitTest assembleHhDebug` → SUCCESSFUL. Коммит: `git commit -am "HHPhone: станция учётки и FCM-токен на HHPBX"` (+ `git add` новых файлов).

### Task 8: Политика фона и пробуждение по push

**Files:**
- Create: `HHPushPolicy.kt`, `app/src/test/kotlin/com/tutpro/baresip/plus/HHPushPolicyTest.kt`
- Modify: `BaresipService.kt` (тик 15 с, выход), `HHPushService.kt` (`onMessageReceived`)

**Interfaces:**
- Consumes: `HHPush.isPush/touch/idleMs` (Task 7).
- Produces: `HHPushPolicy.keep(foreign: Boolean, visible: Boolean, inCall: Boolean, idleMs: Long): Boolean`, `HHPushPolicy.GRACE_MS = 60_000L`.

- [ ] **Step 1: Тест (падает).**

```kotlin
package com.tutpro.baresip.plus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HHPushPolicyTest {
    private val idle = HHPushPolicy.GRACE_MS + 1
    @Test fun foreignAccountKeepsAlways() = assertTrue(HHPushPolicy.keep(foreign = true, visible = false, inCall = false, idleMs = idle))
    @Test fun visibleKeeps() = assertTrue(HHPushPolicy.keep(false, visible = true, inCall = false, idleMs = idle))
    @Test fun callKeeps() = assertTrue(HHPushPolicy.keep(false, false, inCall = true, idleMs = idle))
    @Test fun graceKeeps() = assertTrue(HHPushPolicy.keep(false, false, false, idleMs = HHPushPolicy.GRACE_MS - 1))
    @Test fun idleSleeps() = assertFalse(HHPushPolicy.keep(false, false, false, idleMs = idle))
}
```

Expected: FAIL (нет HHPushPolicy).

- [ ] **Step 2:**

```kotlin
package com.tutpro.baresip.plus

/** Держать ли связь со станциями; иначе HHPhone выходит, следующий звонок разбудит push (спека 2026-10-13, раздел 3). */
object HHPushPolicy {
    const val GRACE_MS = 60_000L
    fun keep(foreign: Boolean, visible: Boolean, inCall: Boolean, idleMs: Long): Boolean =
        foreign || visible || inCall || idleMs < GRACE_MS
}
```

Expected: PASS.

- [ ] **Step 3: Тик в BaresipService.** Поле класса:

```kotlin
    // HH push: когда держать связь не нужно — выйти как «Выход»; звонок разбудит push
    private var hhSleeping = false
    private val hhIdle = object : Runnable {
        override fun run() {
            if (!isServiceRunning) return
            val visible = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
            val inCall = calls.isNotEmpty() || InCallService.instance?.calls?.isNotEmpty() == true
            if (visible || inCall) HHPush.touch()
            val foreign = uas.value.any { it.account.regint > 0 && !HHPush.isPush(it.account.aor) }
            if (HHPushPolicy.keep(foreign, visible, inCall, HHPush.idleMs())) { handler.postDelayed(this, 15_000); return }
            Log.i(TAG, "HHPush: idle, stopping until push")
            hhSleeping = true
            startService(Intent(this@BaresipService, BaresipService::class.java).setAction("Stop"))
        }
    }
```

(`handler` — существующий Handler сервиса; если его нет — `private val handler = Handler(Looper.getMainLooper())`; проверить grep и записать Ruling при отличии. `InCallService.calls` — свойство `android.telecom.InCallService`.)

После `isNativeReady = true`: `HHPush.touch(); handler.postDelayed(hhIdle, 15_000)`.
В `stopped(...)` после `stopSelf()`:

```kotlin
        if (hhSleeping) handler.postDelayed({ exitProcess(0) }, 500)   // как «Выход»: процесс уходит, push запустит заново
```

(импорт `kotlin.system.exitProcess`).

- [ ] **Step 4: Пробуждение.** `HHPushService.onMessageReceived`:

```kotlin
    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["t"] != "call") return
        HHPush.touch()
        if (BaresipService.isServiceRunning) return   // уже на связи
        ContextCompat.startForegroundService(this, Intent(this, BaresipService::class.java).setAction("Start"))
    }
```

- [ ] **Step 5:** `.\gradlew.bat testHhDebugUnitTest assembleHhDebug` → SUCCESSFUL. Коммит `"HHPhone: выход в простое и пробуждение по push"`.

### Task 9: Разрешение батареи и строки

**Files:**
- Modify: `HHHost.kt` (после первой push-учётки), `app/src/hh/res/values{,-ru,-hy}/hh_feature_strings.xml`

- [ ] **Step 1:** Строки:

| name | en | ru | hy |
|---|---|---|---|
| hh_battery_ask | For calls to reach you when HHPhone is closed, allow it to run without battery restrictions. | Чтобы звонки доходили, когда HHPhone закрыт, разрешите ему работать без ограничений батареи. | Որպեսզի զանգերը հասնեն HHPhone-ը փակ լինելիս, թույլ տվեք նրան աշխատել առանց մարտկոցի սահմանափակումների։ |

- [ ] **Step 2:** В `addAccount` после `HHPush.remember(...)`:

```kotlin
val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
if (!pm.isIgnoringBatteryOptimizations(ctx.packageName))
    HHUi.confirm.value = ctx.getString(R.string.hh_battery_ask) to {
        ctx.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${ctx.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
```

Если `HHUi.confirm` уже занят (замена учётки) — показать после него: проверить порядок вручную на эмуляторе; при конфликте — Ruling.

- [ ] **Step 3:** Сборка, коммит `"HHPhone: просьба снять ограничение батареи для push"`.

### Task 10: Стенд, S24 Ultra, исправления

- [ ] **Step 1: Эмулятор.** Проверить образ HHPhone_Test: `avdmanager list avd` → `Based on: Google APIs` или `Google Play`. Если без Google — создать `HHPhone_Push` на `system-images;android-35;google_apis;x86_64` (8 ГБ данных, 2 ГБ RAM). Поставить `assembleHhDebug`… (если не влезает — release-сборку, см. память).
- [ ] **Step 2: Сценарий на эмуляторе** (HHPBX стенд из Task 5 с загруженным ключом пользователя, учётка 101 через QR из панели):
  1. Добавить 101 по QR → в БД `v_hhphone_push` строка для 101; диалог батареи появился.
  2. Свернуть HHPhone, ждать 75–90 с → процесс исчез (`adb shell pidof am.dgsolutions.hhphone` пусто), значка в шторке нет.
  3. Позвонить 100 → 101 (`fs_cli originate` или второй SIP-клиент) → HHPhone звонит в течение 10 с; ответить, разговор, положить.
  4. Добавить «чужую» учётку (QR с паролем) → процесс не выходит через 90 с; удалить её → выходит.
  5. Удалить 101 → строка в `v_hhphone_push` исчезла.
- [ ] **Step 3: S24 Ultra.** Пользователь: «Параметры разработчика» → «Отладка по Wi-Fi» → «Подключить по коду» → присылает порт пары, код и порт подключения. Tailscale-IP телефона — `tailscale status`. `adb pair <ip>:<port> <code>`, `adb connect <ip>:<port2>`. Поставить release-APK поверх (подпись та же). Прогнать:
  - push: телефон заблокирован и спит 5+ мин; HHPhone смахнут из недавних; режим энергосбережения; после перезагрузки телефона;
  - SIM: входящий, исходящий, удержание, второй звонок, Bluetooth (если есть), **112** — только набрать и сразу сбросить до соединения, предупредив пользователя заранее; выбор «SIM или станция» при наборе;
  - экраны звонка в светлой и тёмной теме (снимки `adb exec-out screencap`).
  - Каждую найденную проблему: тест, где возможно → исправление → коммит; в ledger Ruling для неисправленного.
- [ ] **Step 4:** Отключить отладку: `adb disconnect`, попросить пользователя выключить «Отладку по Wi-Fi».

### Task 11: Выпуски

- [ ] **Step 1: HHPBX 1.10.0.** `windows\VERSION` → `1.10.0`, README (Windows и Linux): абзац «Push для HHPhone: ключ Firebase загружается в панели HHPhone». Коммит, merge `feat/hhphone-push` → main, пуш. Сборки: Windows `windows\installer\build.ps1`; Linux `linux/build.sh` на hhpbx-build, `build-repo.sh 1.10.0 …` со старыми deb из `G:\HHPBX-release\1.9.0\Linux\debian\pool\main`; offline-архив; ISO/OVA (`build-iso.sh`, `image-test.sh <iso> .`). Артефакты в `G:\HHPBX-release\1.10.0`, SHA256SUMS, загрузка на filebox (scp в `.uploadNN` → mv), README.txt.
- [ ] **Step 2: HHPhone 0.4.0.** `versionCode = 6`, `versionName = "0.4.0"`, merge `feat/push` → main, пуш, `scripts\release.ps1 -Notes "Звонки доходят, даже когда HHPhone закрыт (push через HHPBX 1.10.0)…"`. Проверить автообновление 0.3.2 → 0.4.0 на S24 (или эмуляторе).
- [ ] **Step 3:** Обновить память: `hh-softphone-project.md` (0.4.0, push, S24 через Tailscale-adb), `hhpbx-linux.md`/CLAUDE.md HHPBX (1.10.0, `/var/lib/hhpbx/push`).
