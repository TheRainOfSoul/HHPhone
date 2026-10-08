# Проверка HHPhone

## На эмуляторе (каждый выпуск)
1. `gradlew testHhDebugUnitTest assembleHhDebug`, `python -I scripts/brand-strings.py --selftest`, `python -I scripts/missing-strings.py`.
2. ВМ hhpbx-test 192.168.109.132 с HHPBX: номера 100/101 (`Tst100pass!x` / `Tst101pass!x`, `linux/tests/vm/call-setup.sh` из HHPBX).
3. Учётка `100@192.168.109.132`, outbound proxy `sip:192.168.109.132;transport=tcp` → регистрация (`fs_cli -x "show registrations"`).
4. Входящий при свёрнутом приложении:
   `fs_cli -x "originate {origination_caller_id_number=101}user/100@192.168.109.132 &park()"` — уведомление «is calling», ответ, сброс с телефона.
5. Исходящий на `*9196` (эхо), видеовызов на `*9196` — на экране эхо камеры.
6. Языки: `adb shell cmd locale set-app-locales am.dgsolutions.hhphone --locales hy|ru|en`; при системном языке не из трёх — русский.

### Особенности стенда (не ошибки приложения)
- **Эмулятор за двойным NAT** (10.0.2.16 → 192.168.109.1). АТС не видит NAT (источник из её локальной сети) и шлёт INVITE на 10.0.2.16 —
  входящие не доходят. На стенде у номера 100: `update v_extensions set sip_force_contact='NDLB-connectile-dysfunction' where extension='100'`.
  Телефон в той же сети, что АТС, этого не требует.
- **Видео**: HHPBX по умолчанию без видеокодеков (`global_codec_prefs` = только аудио). На стенде в `/etc/freeswitch/vars.xml`
  к `global_codec_prefs` и `outbound_codec_prefs` добавлено `,VP8,H264`, затем очистка `/var/cache/fusionpbx`, `reloadxml`,
  `sofia profile internal restart`. Работает VP8 (H264 в FreeSWITCH HHPBX нет — нет mod_h26x/mod_av).

## На реальном телефоне (пользователь)
1. Установить APK из GitHub Releases, разрешить микрофон, камеру, уведомления, «Устройства поблизости».
2. В настройках Android снять оптимизацию батареи для HHPhone (Xiaomi: «Автозапуск» вкл., «Контроль активности» → «Нет ограничений»;
   Huawei: «Запуск приложений» → вручную, все три переключателя).
3. Добавить учётку, заблокировать экран, позвонить на номер с другого телефона через 10 минут — звонок должен прийти.
4. Видеозвонок (нужны видеокодеки в АТС), Bluetooth-гарнитура: ответ кнопкой гарнитуры.
