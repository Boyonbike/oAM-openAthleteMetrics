(module
;; Memory map (5 pages = 320 KB):
;;   0x0000-0x000F  spec-v2 metadata (syncStartMs i64 LE @0, utcOffsetMinutes i16 LE @8)
;;   0x0010-0xCFFF  frames JSON input written by engine (up to ~52 KB)
;;   0x0400         CMD_OUT_OFFSET — buildSyncCommands output (engine reads here)
;;   0x1000         OUT_OFFSET     — parseSession final output (engine reads here)
;;   0x10000        TEMP_OUT — parseSession builds output here, then wmem→0x1000
;;   0x3FC00-0x3FFFF  B64_SCRATCH — one decoded frame (1024 bytes)
;;   0x40000-0x400E0  static strings (metric types, JSON templates, unit strings)
;;   0x400E2-0x400F9  month-day offset table (12 × i16 LE)
;;   0x40100-0x401FF  base64 decode table (256 bytes)
;;   0x40200-0x4025F  scan patterns (opcode, bytes field, SyncContext field names)
;;   0x40300-0x403E8  buildSyncCommands JSON templates
;;   0x40400-0x404AF  sleep stage metaJson prefix strings + shared suffixes
;;   0x40500-0x407E6  newer sync-command templates (ENABLE_NOTIFY/handshake/fetch strings)
;;   0x40800-0x4089F  scratch (int-to-string, YMD buf, raw command bytes)
;;
;; The static-tables-and-scratch block above lives on its own page (page 5), entirely
;; disjoint from parseSession's output-copy destination (0x1000 onward) and TEMP_OUT's
;; growth range (0x10000 onward). This is deliberate: parseSession's final copy from
;; TEMP_OUT down to OUT_OFFSET is unbounded, and this block previously sat at 0xD800 —
;; inside that copy's reach for any out_len > 0xC800 (51,200) bytes — silently corrupting
;; these tables for the remainder of a multi-chunk session. Do not move this block back
;; into [0x1000, 0x40000).
(memory (export "memory") 5)

;; ── STATIC DATA ─────────────────────────────────────────────────────────────

;; Main string constants at 0xD800 (one contiguous block, 225 bytes through 0xD8E0)
(data (i32.const 0x40000)
;; [D800] 15  {"metricType":"
"\7b\22\6d\65\74\72\69\63\54\79\70\65\22\3a\22"
;; [D80F] 10  ","value":
"\22\2c\22\76\61\6c\75\65\22\3a"
;; [D819]  9  ,"unit":"
"\2c\22\75\6e\69\74\22\3a\22"
;; [D822] 17  ","recordedAtMs":
"\22\2c\22\72\65\63\6f\72\64\65\64\41\74\4d\73\22\3a"
;; [D833]  2  HR
"HR"
;; [D835]  3  HRV
"HRV"
;; [D838]  4  SPO2
"SPO2"
;; [D83C]  5  STEPS
"STEPS"
;; [D841]  7  BATTERY
"BATTERY"
;; [D848]  9  SKIN_TEMP
"SKIN_TEMP"
;; [D851] 15  ACTIVE_CALORIES
"ACTIVE_CALORIES"
;; [D860]  8  DISTANCE
"DISTANCE"
;; [D868] 14  BLOOD_PRESSURE
"BLOOD_PRESSURE"
;; [D876] 11  SLEEP_STAGE
"SLEEP_STAGE"
;; [D881]  3  bpm
"bpm"
;; [D884]  2  ms
"ms"
;; [D886]  1  %
"%"
;; [D887]  5  steps
"steps"
;; [D88C]  4  kcal
"kcal"
;; [D890]  1  m
"m"
;; [D891]  3  °C  (UTF-8: C2 B0 43)
"\c2\b0\43"
;; [D894]  4  mmHg
"mmHg"
;; [D898]  5  stage
"stage"
;; [D89D]  1  }   (closes simple metric object)
"}"
;; [D89E] 19  ,"confidence":null}   — suffix for simple metrics
"\2c\22\63\6f\6e\66\69\64\65\6e\63\65\22\3a\6e\75\6c\6c\7d"
;; [D8B1] 31  ,"confidence":null,"metaJson":"   — prefix for metaJson metrics
"\2c\22\63\6f\6e\66\69\64\65\6e\63\65\22\3a\6e\75\6c\6c\2c\22\6d\65\74\61\4a\73\6f\6e\22\3a\22"
;; [D8D0]  2  "}   — closes metaJson string + outer metric object
"\22\7d"
;; [D8D2] 15  {\"diastolic\":   — escaped BP metaJson prefix
"\7b\5c\22\64\69\61\73\74\6f\6c\69\63\5c\22\3a"
)
;; [D8E1] end of string block

;; Month-day offset table at 0xD8E2 (12 × i16 LE: cumulative days at start of each month)
;; Jan=0, Feb=31, Mar=59, Apr=90, May=120, Jun=151, Jul=181, Aug=212, Sep=243, Oct=273, Nov=304, Dec=334
(data (i32.const 0x400E2)
"\00\00\1f\00\3b\00\5a\00\78\00\97\00\b5\00\d4\00\f3\00\11\01\30\01\4e\01"
)

;; Base64 decode table at 0xD900 (256 bytes): index=ASCII code, value=6-bit decoded or 0xFF
(data (i32.const 0x40100)
"\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff"
"\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff"
"\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\3e\ff\ff\ff\3f"
"\34\35\36\37\38\39\3a\3b\3c\3d\ff\ff\ff\00\ff\ff"
"\ff\00\01\02\03\04\05\06\07\08\09\0a\0b\0c\0d\0e"
"\0f\10\11\12\13\14\15\16\17\18\19\ff\ff\ff\ff\ff"
"\ff\1a\1b\1c\1d\1e\1f\20\21\22\23\24\25\26\27\28"
"\29\2a\2b\2c\2d\2e\2f\30\31\32\33\ff\ff\ff\ff\ff"
"\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff"
"\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff"
"\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff"
"\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff"
"\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff"
"\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff"
"\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff"
"\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff\ff"
)

;; Scan patterns at 0xDA00
(data (i32.const 0x40200)
;; [DA00] 12  "opcode":"0x
"\22\6f\70\63\6f\64\65\22\3a\22\30\78"
;; [DA0C]  9  "bytes":"
"\22\62\79\74\65\73\22\3a\22"
;; [DA15] 21  "biologicalSex":"male"
"\22\62\69\6f\6c\6f\67\69\63\61\6c\53\65\78\22\3a\22\6d\61\6c\65"
;; [DA29]  1  "   (extra " to complete "male" pattern — now len=21 total)
"\22"
;; [DA2A] 15  "dateOfBirth":"
"\22\64\61\74\65\4f\66\42\69\72\74\68\22\3a\22"
;; [DA39] 11  "heightCm":
"\22\68\65\69\67\68\74\43\6d\22\3a"
;; [DA44] 11  "weightKg":
"\22\77\65\69\67\68\74\4b\67\22\3a"
;; [DA4F] 17  "strideLengthCm":
"\22\73\74\72\69\64\65\4c\65\6e\67\74\68\43\6d\22\3a"
)

;; buildSyncCommands JSON templates at 0xDB00
(data (i32.const 0x40300)
;; [DB00] 51  [{"type":"WRITE","characteristic":"write","bytes":"
"\5b\7b\22\74\79\70\65\22\3a\22\57\52\49\54\45\22\2c\22\63\68\61\72\61\63\74\65\72\69\73\74\69\63\22\3a\22\77\72\69\74\65\22\2c\22\62\79\74\65\73\22\3a\22"
;; [DB33] 64  ","awaitReply":{"characteristicRole":"notify","timeoutMs":5000}}
"\22\2c\22\61\77\61\69\74\52\65\70\6c\79\22\3a\7b\22\63\68\61\72\61\63\74\65\72\69\73\74\69\63\52\6f\6c\65\22\3a\22\6e\6f\74\69\66\79\22\2c\22\74\69\6d\65\6f\75\74\4d\73\22\3a\35\30\30\30\7d\7d"
;; [DB73] 51  ,{"type":"WRITE","characteristic":"write","bytes":"
"\2c\7b\22\74\79\70\65\22\3a\22\57\52\49\54\45\22\2c\22\63\68\61\72\61\63\74\65\72\69\73\74\69\63\22\3a\22\77\72\69\74\65\22\2c\22\62\79\74\65\73\22\3a\22"
;; [DBA6] 65  ","awaitReply":{"characteristicRole":"notify","timeoutMs":5000}}]
"\22\2c\22\61\77\61\69\74\52\65\70\6c\79\22\3a\7b\22\63\68\61\72\61\63\74\65\72\69\73\74\69\63\52\6f\6c\65\22\3a\22\6e\6f\74\69\66\79\22\2c\22\74\69\6d\65\6f\75\74\4d\73\22\3a\35\30\30\30\7d\7d\5d"
)

;; Sleep stage metaJson prefix strings + shared suffixes at 0xDC00
;; All pre-escaped for embedding inside a JSON string value.
(data (i32.const 0x40400)
;; [DC00] 34  {\"stage\":\"AWAKE\",\"start_ms\":
"\7b\5c\22\73\74\61\67\65\5c\22\3a\5c\22\41\57\41\4b\45\5c\22\2c\5c\22\73\74\61\72\74\5f\6d\73\5c\22\3a"
;; [DC22] 34  {\"stage\":\"LIGHT\",\"start_ms\":
"\7b\5c\22\73\74\61\67\65\5c\22\3a\5c\22\4c\49\47\48\54\5c\22\2c\5c\22\73\74\61\72\74\5f\6d\73\5c\22\3a"
;; [DC44] 33  {\"stage\":\"DEEP\",\"start_ms\":
"\7b\5c\22\73\74\61\67\65\5c\22\3a\5c\22\44\45\45\50\5c\22\2c\5c\22\73\74\61\72\74\5f\6d\73\5c\22\3a"
;; [DC65] 32  {\"stage\":\"REM\",\"start_ms\":
"\7b\5c\22\73\74\61\67\65\5c\22\3a\5c\22\52\45\4d\5c\22\2c\5c\22\73\74\61\72\74\5f\6d\73\5c\22\3a"
;; [DC85] 12  ,\"end_ms\":
"\2c\5c\22\65\6e\64\5f\6d\73\5c\22\3a"
;; [DC91] 30  ,\"pending_sleep_stage\":true}
"\2c\5c\22\70\65\6e\64\69\6e\67\5f\73\6c\65\65\70\5f\73\74\61\67\65\5c\22\3a\74\72\75\65\7d"
)

;; New sync command templates at 0xDD00
(data (i32.const 0x40500)
;; [DD00] 51  [{"type":"ENABLE_NOTIFY","characteristic":"notify"}
"\5b\7b\22\74\79\70\65\22\3a\22\45\4e\41\42\4c\45\5f\4e\4f\54\49\46\59\22\2c\22\63\68\61\72\61\63\74\65\72\69\73\74\69\63\22\3a\22\6e\6f\74\69\66\79\22\7d"
;; [DD33] 194  ,{"type":"WRITE","characteristic":"write","bytes":"0x13 0x00×14 0x13","awaitReply":{"characteristicRole":"notify","timeoutMs":5000}}
"\2c\7b\22\74\79\70\65\22\3a\22\57\52\49\54\45\22\2c\22\63\68\61\72\61\63\74\65\72\69\73\74\69\63\22\3a\22\77\72\69\74\65\22\2c\22\62\79\74\65\73\22\3a\22\30\78\31\33\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\31\33\22\2c\22\61\77\61\69\74\52\65\70\6c\79\22\3a\7b\22\63\68\61\72\61\63\74\65\72\69\73\74\69\63\52\6f\6c\65\22\3a\22\6e\6f\74\69\66\79\22\2c\22\74\69\6d\65\6f\75\74\4d\73\22\3a\35\30\30\30\7d\7d"
;; [DDF5] 102  ","awaitEndOfStream":{"characteristic":"notify","endByte":{"offset":1,"value":255},"timeoutMs":30000}}
"\22\2c\22\61\77\61\69\74\45\6e\64\4f\66\53\74\72\65\61\6d\22\3a\7b\22\63\68\61\72\61\63\74\65\72\69\73\74\69\63\22\3a\22\6e\6f\74\69\66\79\22\2c\22\65\6e\64\42\79\74\65\22\3a\7b\22\6f\66\66\73\65\74\22\3a\31\2c\22\76\61\6c\75\65\22\3a\32\35\35\7d\2c\22\74\69\6d\65\6f\75\74\4d\73\22\3a\33\30\30\30\30\7d\7d"
;; [DE5B] 79  0x55 0x00×14 0x55  (bytes field text, no trailing space)
"\30\78\35\35\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\35\35"
;; [DEAA] 79  0x52 bytes string
"\30\78\35\32\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\35\32"
;; [DEF9] 79  0x53 bytes string
"\30\78\35\33\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\35\33"
;; [DF48] 79  0x66 bytes string
"\30\78\36\36\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\36\36"
;; [DF97] 79  0x56 bytes string
"\30\78\35\36\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\35\36"
;; [DFE6] 79  0x65 bytes string — ends at 0xE035, safe before 0xE080
"\30\78\36\35\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\30\30\20\30\78\36\35"
)

;; ── GLOBALS ──────────────────────────────────────────────────────────────────

(global $need_comma (mut i32) (i32.const 0))

;; ── UTILITY: write helpers ───────────────────────────────────────────────────

(func $bcd (param $b i32) (result i32)
(i32.add
(i32.mul
(i32.and (i32.shr_u (local.get $b) (i32.const 4)) (i32.const 15))
(i32.const 10))
(i32.and (local.get $b) (i32.const 15))))

(func $leap (param $y i32) (result i32)
(i32.or
(i32.and
(i32.eqz (i32.rem_u (local.get $y) (i32.const 4)))
(i32.ne  (i32.rem_u (local.get $y) (i32.const 100)) (i32.const 0)))
(i32.eqz (i32.rem_u (local.get $y) (i32.const 400)))))

(func $days_epoch (param $y i32) (param $m i32) (param $d i32) (result i32)
(local $days i32)
(local.set $days (i32.mul (i32.sub (local.get $y) (i32.const 1970)) (i32.const 365)))
(local.set $days (i32.add (local.get $days)
(i32.div_u (i32.sub (local.get $y) (i32.const 1969)) (i32.const 4))))
(local.set $days (i32.add (local.get $days)
(i32.load16_u
(i32.add (i32.const 0x400E2)
  (i32.mul (i32.sub (local.get $m) (i32.const 1)) (i32.const 2))))))
(if (i32.and (call $leap (local.get $y)) (i32.gt_u (local.get $m) (i32.const 2)))
(then (local.set $days (i32.add (local.get $days) (i32.const 1)))))
(i32.add (local.get $days) (i32.sub (local.get $d) (i32.const 1))))

(func $bcd_ts (param $base i32) (result i64)
(local $y i32) (local $mo i32) (local $d i32)
(local $h i32) (local $mi i32) (local $s i32)
(local.set $y  (i32.add (call $bcd (i32.load8_u (local.get $base)))              (i32.const 2000)))
(local.set $mo (call $bcd (i32.load8_u (i32.add (local.get $base) (i32.const 1)))))
(local.set $d  (call $bcd (i32.load8_u (i32.add (local.get $base) (i32.const 2)))))
(local.set $h  (call $bcd (i32.load8_u (i32.add (local.get $base) (i32.const 3)))))
(local.set $mi (call $bcd (i32.load8_u (i32.add (local.get $base) (i32.const 4)))))
(local.set $s  (call $bcd (i32.load8_u (i32.add (local.get $base) (i32.const 5)))))
(i64.mul
(i64.add
(i64.add
  (i64.add
    (i64.mul (i64.extend_i32_u (call $days_epoch (local.get $y) (local.get $mo) (local.get $d)))
             (i64.const 86400))
    (i64.mul (i64.extend_i32_u (local.get $h)) (i64.const 3600)))
  (i64.mul (i64.extend_i32_u (local.get $mi)) (i64.const 60)))
(i64.extend_i32_u (local.get $s)))
(i64.const 1000)))

(func $le16 (param $base i32) (result i32)
(i32.or
(i32.load8_u (local.get $base))
(i32.shl (i32.load8_u (i32.add (local.get $base) (i32.const 1))) (i32.const 8))))

(func $wc (param $ptr i32) (param $ch i32) (result i32)
(i32.store8 (local.get $ptr) (local.get $ch))
(i32.add (local.get $ptr) (i32.const 1)))

(func $wmem (param $dst i32) (param $src i32) (param $n i32) (result i32)
(local $i i32)
(local.set $i (i32.const 0))
(block $done
(loop $L
(br_if $done (i32.ge_u (local.get $i) (local.get $n)))
(i32.store8
  (i32.add (local.get $dst) (local.get $i))
  (i32.load8_u (i32.add (local.get $src) (local.get $i))))
(local.set $i (i32.add (local.get $i) (i32.const 1)))
(br $L)))
(i32.add (local.get $dst) (local.get $n)))

;; write_u32: scratch at 0xE000 (32 bytes)
(func $write_u32 (param $v i32) (param $p i32) (result i32)
(local $lo i32) (local $hi i32) (local $len i32) (local $c i32)
(if (i32.eqz (local.get $v))
(then (return (call $wc (local.get $p) (i32.const 48)))))
(local.set $lo (i32.const 0x40800))
(local.set $hi (i32.const 0x40800))
(block $done
(loop $L
(br_if $done (i32.eqz (local.get $v)))
(i32.store8 (local.get $hi)
  (i32.add (i32.const 48) (i32.rem_u (local.get $v) (i32.const 10))))
(local.set $hi (i32.add (local.get $hi) (i32.const 1)))
(local.set $v  (i32.div_u (local.get $v) (i32.const 10)))
(br $L)))
(local.set $len (i32.sub (local.get $hi) (i32.const 0x40800)))
(local.set $hi (i32.sub (local.get $hi) (i32.const 1)))
(block $rdone
(loop $R
(br_if $rdone (i32.ge_u (local.get $lo) (local.get $hi)))
(local.set $c (i32.load8_u (local.get $lo)))
(i32.store8 (local.get $lo) (i32.load8_u (local.get $hi)))
(i32.store8 (local.get $hi) (local.get $c))
(local.set $lo (i32.add (local.get $lo) (i32.const 1)))
(local.set $hi (i32.sub (local.get $hi) (i32.const 1)))
(br $R)))
(call $wmem (local.get $p) (i32.const 0x40800) (local.get $len)))

;; write_u64: scratch at 0xE020 (32 bytes)
(func $write_u64 (param $v i64) (param $p i32) (result i32)
(local $lo i32) (local $hi i32) (local $len i32) (local $c i32)
(if (i64.eqz (local.get $v))
(then (return (call $wc (local.get $p) (i32.const 48)))))
(local.set $lo (i32.const 0x40820))
(local.set $hi (i32.const 0x40820))
(block $done
(loop $L
(br_if $done (i64.eqz (local.get $v)))
(i32.store8 (local.get $hi)
  (i32.add (i32.const 48) (i32.wrap_i64 (i64.rem_u (local.get $v) (i64.const 10)))))
(local.set $hi (i32.add (local.get $hi) (i32.const 1)))
(local.set $v  (i64.div_u (local.get $v) (i64.const 10)))
(br $L)))
(local.set $len (i32.sub (local.get $hi) (i32.const 0x40820)))
(local.set $hi (i32.sub (local.get $hi) (i32.const 1)))
(block $rdone
(loop $R
(br_if $rdone (i32.ge_u (local.get $lo) (local.get $hi)))
(local.set $c (i32.load8_u (local.get $lo)))
(i32.store8 (local.get $lo) (i32.load8_u (local.get $hi)))
(i32.store8 (local.get $hi) (local.get $c))
(local.set $lo (i32.add (local.get $lo) (i32.const 1)))
(local.set $hi (i32.sub (local.get $hi) (i32.const 1)))
(br $R)))
(call $wmem (local.get $p) (i32.const 0x40820) (local.get $len)))

(func $write_1dp (param $v i32) (param $p i32) (result i32)
(local.set $p (call $write_u32 (i32.div_u (local.get $v) (i32.const 10)) (local.get $p)))
(local.set $p (call $wc (local.get $p) (i32.const 46)))
(call $wc (local.get $p)
(i32.add (i32.const 48) (i32.rem_u (local.get $v) (i32.const 10)))))

(func $write_2dp (param $v i32) (param $p i32) (result i32)
(local $frac i32) (local $p2 i32)
(local.set $frac (i32.rem_u (local.get $v) (i32.const 100)))
(local.set $p2   (call $write_u32 (i32.div_u (local.get $v) (i32.const 100)) (local.get $p)))
(local.set $p2   (call $wc (local.get $p2) (i32.const 46)))
(if (i32.lt_u (local.get $frac) (i32.const 10))
(then (local.set $p2 (call $wc (local.get $p2) (i32.const 48)))))
(call $write_u32 (local.get $frac) (local.get $p2)))

;; ── COMMA / PRE-EMIT ────────────────────────────────────────────────────────

(func $pre_emit (param $p i32) (result i32)
(if (global.get $need_comma)
(then (local.set $p (call $wc (local.get $p) (i32.const 44)))))
(global.set $need_comma (i32.const 1))
(local.get $p))

;; ── METRIC JSON EMITTERS ─────────────────────────────────────────────────────
;;
;; Format: {"metricType":"TYPE","value":V,"unit":"U","recordedAtMs":T,"confidence":null}
;; For metaJson metrics: ...,"confidence":null,"metaJson":"ESCAPED"}

(func $emit_int
(param $tp i32) (param $tl i32)
(param $up i32) (param $ul i32)
(param $val i32) (param $ts i64)
(param $p i32) (result i32)
(local.set $p (call $wmem (local.get $p) (i32.const 0x40000) (i32.const 15)))
(local.set $p (call $wmem (local.get $p) (local.get $tp) (local.get $tl)))
(local.set $p (call $wmem (local.get $p) (i32.const 0x4000F) (i32.const 10)))
(local.set $p (call $write_u32 (local.get $val) (local.get $p)))
(local.set $p (call $wmem (local.get $p) (i32.const 0x40019) (i32.const 9)))
(local.set $p (call $wmem (local.get $p) (local.get $up) (local.get $ul)))
(local.set $p (call $wmem (local.get $p) (i32.const 0x40022) (i32.const 17)))
(local.set $p (call $write_u64 (local.get $ts) (local.get $p)))
(call $wmem (local.get $p) (i32.const 0x4009E) (i32.const 19)))

(func $emit_1dp
(param $tp i32) (param $tl i32)
(param $up i32) (param $ul i32)
(param $val i32) (param $ts i64)
(param $p i32) (result i32)
(local.set $p (call $wmem (local.get $p) (i32.const 0x40000) (i32.const 15)))
(local.set $p (call $wmem (local.get $p) (local.get $tp) (local.get $tl)))
(local.set $p (call $wmem (local.get $p) (i32.const 0x4000F) (i32.const 10)))
(local.set $p (call $write_1dp (local.get $val) (local.get $p)))
(local.set $p (call $wmem (local.get $p) (i32.const 0x40019) (i32.const 9)))
(local.set $p (call $wmem (local.get $p) (local.get $up) (local.get $ul)))
(local.set $p (call $wmem (local.get $p) (i32.const 0x40022) (i32.const 17)))
(local.set $p (call $write_u64 (local.get $ts) (local.get $p)))
(call $wmem (local.get $p) (i32.const 0x4009E) (i32.const 19)))

(func $emit_2dp
(param $tp i32) (param $tl i32)
(param $up i32) (param $ul i32)
(param $val i32) (param $ts i64)
(param $p i32) (result i32)
(local.set $p (call $wmem (local.get $p) (i32.const 0x40000) (i32.const 15)))
(local.set $p (call $wmem (local.get $p) (local.get $tp) (local.get $tl)))
(local.set $p (call $wmem (local.get $p) (i32.const 0x4000F) (i32.const 10)))
(local.set $p (call $write_2dp (local.get $val) (local.get $p)))
(local.set $p (call $wmem (local.get $p) (i32.const 0x40019) (i32.const 9)))
(local.set $p (call $wmem (local.get $p) (local.get $up) (local.get $ul)))
(local.set $p (call $wmem (local.get $p) (i32.const 0x40022) (i32.const 17)))
(local.set $p (call $write_u64 (local.get $ts) (local.get $p)))
(call $wmem (local.get $p) (i32.const 0x4009E) (i32.const 19)))

;; BLOOD_PRESSURE: value=systolic, metaJson={"diastolic":D}
(func $emit_bp
(param $systolic i32) (param $diastolic i32) (param $ts i64)
(param $p i32) (result i32)
(local.set $p (call $wmem (local.get $p) (i32.const 0x40000) (i32.const 15)))
(local.set $p (call $wmem (local.get $p) (i32.const 0x40068) (i32.const 14)))
(local.set $p (call $wmem (local.get $p) (i32.const 0x4000F) (i32.const 10)))
(local.set $p (call $write_u32 (local.get $systolic) (local.get $p)))
(local.set $p (call $wmem (local.get $p) (i32.const 0x40019) (i32.const 9)))
(local.set $p (call $wmem (local.get $p) (i32.const 0x40094) (i32.const 4)))
(local.set $p (call $wmem (local.get $p) (i32.const 0x40022) (i32.const 17)))
(local.set $p (call $write_u64 (local.get $ts) (local.get $p)))
(local.set $p (call $wmem (local.get $p) (i32.const 0x400B1) (i32.const 31)))
(local.set $p (call $wmem (local.get $p) (i32.const 0x400D2) (i32.const 15)))
(local.set $p (call $write_u32 (local.get $diastolic) (local.get $p)))
(local.set $p (call $wmem (local.get $p) (i32.const 0x4009D) (i32.const 1)))
(call $wmem (local.get $p) (i32.const 0x400D0) (i32.const 2)))

;; SLEEP_STAGE: value=0, unit="stage", metaJson with stage/start_ms/end_ms/pending flag
(func $emit_sleep_stage
(param $mj_ptr i32) (param $mj_len i32)
(param $start_ms i64) (param $end_ms i64)
(param $ts i64)
(param $p i32) (result i32)
(local.set $p (call $wmem (local.get $p) (i32.const 0x40000) (i32.const 15)))
(local.set $p (call $wmem (local.get $p) (i32.const 0x40076) (i32.const 11)))
(local.set $p (call $wmem (local.get $p) (i32.const 0x4000F) (i32.const 10)))
(local.set $p (call $wc   (local.get $p) (i32.const 48)))
(local.set $p (call $wmem (local.get $p) (i32.const 0x40019) (i32.const 9)))
(local.set $p (call $wmem (local.get $p) (i32.const 0x40098) (i32.const 5)))
(local.set $p (call $wmem (local.get $p) (i32.const 0x40022) (i32.const 17)))
(local.set $p (call $write_u64 (local.get $ts) (local.get $p)))
(local.set $p (call $wmem (local.get $p) (i32.const 0x400B1) (i32.const 31)))
(local.set $p (call $wmem (local.get $p) (local.get $mj_ptr) (local.get $mj_len)))
(local.set $p (call $write_u64 (local.get $start_ms) (local.get $p)))
(local.set $p (call $wmem (local.get $p) (i32.const 0x40485) (i32.const 12)))
(local.set $p (call $write_u64 (local.get $end_ms) (local.get $p)))
(local.set $p (call $wmem (local.get $p) (i32.const 0x40491) (i32.const 30)))
(call $wmem (local.get $p) (i32.const 0x400D0) (i32.const 2)))

;; ── OPCODE HANDLERS ─────────────────────────────────────────────────────────
;; $base = pointer to decoded raw BLE bytes at 0x3FC00 (B64_SCRATCH).
;; $p    = current output write pointer in temp buffer.
;; Returns updated output ptr.

(func $do_hr (param $base i32) (param $p i32) (result i32)
(local.set $p (call $pre_emit (local.get $p)))
(call $emit_int
(i32.const 0x40033) (i32.const 2)
(i32.const 0x40081) (i32.const 3)
(i32.load8_u (i32.add (local.get $base) (i32.const 9)))
(call $bcd_ts (i32.add (local.get $base) (i32.const 3)))
(local.get $p)))

(func $do_steps (param $base i32) (param $p i32) (result i32)
(local $ts i64)
(local.set $ts (call $bcd_ts (i32.add (local.get $base) (i32.const 3))))
(local.set $p (call $pre_emit (local.get $p)))
(local.set $p
(call $emit_int
(i32.const 0x4003C) (i32.const 5)
(i32.const 0x40087) (i32.const 5)
(call $le16 (i32.add (local.get $base) (i32.const 9)))
(local.get $ts)
(local.get $p)))
(local.set $p (call $pre_emit (local.get $p)))
(local.set $p
(call $emit_2dp
(i32.const 0x40051) (i32.const 15)
(i32.const 0x4008C) (i32.const 4)
(call $le16 (i32.add (local.get $base) (i32.const 11)))
(local.get $ts)
(local.get $p)))
(local.set $p (call $pre_emit (local.get $p)))
(call $emit_int
(i32.const 0x40060) (i32.const 8)
(i32.const 0x40090) (i32.const 1)
(i32.mul (call $le16 (i32.add (local.get $base) (i32.const 13))) (i32.const 10))
(local.get $ts)
(local.get $p)))

(func $do_hrv (param $base i32) (param $rawlen i32) (param $p i32) (result i32)
(local $offset i32) (local $rec i32) (local $mo i32)
(local $ts i64) (local $sys i32) (local $dia i32) (local $hrv_val i32)
(local.set $offset (i32.const 0))
(block $loop_done
(loop $rec_loop
  (br_if $loop_done (i32.gt_u (i32.add (local.get $offset) (i32.const 15)) (local.get $rawlen)))
  (local.set $rec (i32.add (local.get $base) (local.get $offset)))
  (br_if $loop_done (i32.ne (i32.load8_u (local.get $rec)) (i32.const 0x56)))
  (br_if $loop_done (i32.eq (i32.load8_u (i32.add (local.get $rec) (i32.const 1))) (i32.const 0xFF)))

  (local.set $mo (call $bcd (i32.load8_u (i32.add (local.get $rec) (i32.const 4)))))
  (if (i32.eqz (local.get $mo))
    (then
      (local.set $offset (i32.add (local.get $offset) (i32.const 15)))
      (br $rec_loop)))

  (local.set $ts      (call $bcd_ts (i32.add (local.get $rec) (i32.const 3))))
  (local.set $hrv_val (i32.load8_u (i32.add (local.get $rec) (i32.const 9))))
  (local.set $sys     (i32.load8_u (i32.add (local.get $rec) (i32.const 13))))
  (local.set $dia     (i32.load8_u (i32.add (local.get $rec) (i32.const 14))))

  (local.set $p (call $pre_emit (local.get $p)))
  (local.set $p
    (call $emit_int
      (i32.const 0x40035) (i32.const 3)
      (i32.const 0x40084) (i32.const 2)
      (local.get $hrv_val)
      (local.get $ts)
      (local.get $p)))

  (if (i32.and
        (i32.ne (local.get $sys) (i32.const 0))
        (i32.ne (local.get $dia) (i32.const 0)))
    (then
      (local.set $p (call $pre_emit (local.get $p)))
      (local.set $p
        (call $emit_bp (local.get $sys) (local.get $dia) (local.get $ts) (local.get $p)))))

  (local.set $offset (i32.add (local.get $offset) (i32.const 15)))
  (br $rec_loop)))
(local.get $p))

(func $do_spo2 (param $base i32) (param $p i32) (result i32)
(local.set $p (call $pre_emit (local.get $p)))
(call $emit_int
(i32.const 0x40038) (i32.const 4)
(i32.const 0x40086) (i32.const 1)
(i32.load8_u (i32.add (local.get $base) (i32.const 9)))
(call $bcd_ts (i32.add (local.get $base) (i32.const 3)))
(local.get $p)))

(func $do_temp (param $base i32) (param $p i32) (result i32)
(local $raw i32)
(local.set $raw (call $le16 (i32.add (local.get $base) (i32.const 9))))
(if (i32.eqz (local.get $raw)) (then (return (local.get $p))))
(local.set $p (call $pre_emit (local.get $p)))
(call $emit_1dp
(i32.const 0x40048) (i32.const 9)
(i32.const 0x40091) (i32.const 3)
(local.get $raw)
(call $bcd_ts (i32.add (local.get $base) (i32.const 3)))
(local.get $p)))

;; Sleep: run-length encode stage bytes into SLEEP_STAGE readings
(func $do_sleep (param $base i32) (param $p i32) (result i32)
(local $count i32) (local $i i32)
(local $cur_stage i32) (local $span_start i32)
(local $ts_start i64)
(local $stage_b i32) (local $mj_ptr i32) (local $mj_len i32)

(local.set $count (i32.load8_u (i32.add (local.get $base) (i32.const 9))))
(if (i32.eqz (local.get $count)) (then (return (local.get $p))))
(local.set $ts_start (call $bcd_ts (i32.add (local.get $base) (i32.const 3))))
(local.set $cur_stage (i32.const 0xFF))
(local.set $span_start (i32.const 0))
(local.set $i (i32.const 0))

(block $sdone
(loop $SL
(br_if $sdone (i32.ge_u (local.get $i) (local.get $count)))
(local.set $stage_b
  (i32.load8_u
    (i32.add (i32.add (local.get $base) (i32.const 10)) (local.get $i))))
(if (i32.ne (local.get $stage_b) (local.get $cur_stage))
  (then
    (if (i32.ne (local.get $cur_stage) (i32.const 0xFF))
      (then
        (local.set $mj_ptr (i32.const 0)) (local.set $mj_len (i32.const 0))
        (if (i32.eq (local.get $cur_stage) (i32.const 1))
          (then (local.set $mj_ptr (i32.const 0x40400)) (local.set $mj_len (i32.const 34))))
        (if (i32.eq (local.get $cur_stage) (i32.const 2))
          (then (local.set $mj_ptr (i32.const 0x40422)) (local.set $mj_len (i32.const 34))))
        (if (i32.eq (local.get $cur_stage) (i32.const 3))
          (then (local.set $mj_ptr (i32.const 0x40444)) (local.set $mj_len (i32.const 33))))
        (if (i32.eq (local.get $cur_stage) (i32.const 5))
          (then (local.set $mj_ptr (i32.const 0x40465)) (local.set $mj_len (i32.const 32))))
        (if (i32.ne (local.get $mj_len) (i32.const 0))
          (then
            (local.set $p (call $pre_emit (local.get $p)))
            (local.set $p
              (call $emit_sleep_stage
                (local.get $mj_ptr) (local.get $mj_len)
                (i64.add (local.get $ts_start)
                  (i64.mul (i64.extend_i32_u (local.get $span_start)) (i64.const 60000)))
                (i64.add (local.get $ts_start)
                  (i64.mul (i64.extend_i32_u (local.get $i)) (i64.const 60000)))
                (i64.add (local.get $ts_start)
                  (i64.mul (i64.extend_i32_u (local.get $span_start)) (i64.const 60000)))
                (local.get $p)))))))
    (local.set $cur_stage (local.get $stage_b))
    (local.set $span_start (local.get $i))))
(local.set $i (i32.add (local.get $i) (i32.const 1)))
(br $SL)))

;; flush last span
(local.set $mj_ptr (i32.const 0)) (local.set $mj_len (i32.const 0))
(if (i32.eq (local.get $cur_stage) (i32.const 1))
(then (local.set $mj_ptr (i32.const 0x40400)) (local.set $mj_len (i32.const 34))))
(if (i32.eq (local.get $cur_stage) (i32.const 2))
(then (local.set $mj_ptr (i32.const 0x40422)) (local.set $mj_len (i32.const 34))))
(if (i32.eq (local.get $cur_stage) (i32.const 3))
(then (local.set $mj_ptr (i32.const 0x40444)) (local.set $mj_len (i32.const 33))))
(if (i32.eq (local.get $cur_stage) (i32.const 5))
(then (local.set $mj_ptr (i32.const 0x40465)) (local.set $mj_len (i32.const 32))))
(if (i32.ne (local.get $mj_len) (i32.const 0))
(then
(local.set $p (call $pre_emit (local.get $p)))
(local.set $p
  (call $emit_sleep_stage
    (local.get $mj_ptr) (local.get $mj_len)
    (i64.add (local.get $ts_start)
      (i64.mul (i64.extend_i32_u (local.get $span_start)) (i64.const 60000)))
    (i64.add (local.get $ts_start)
      (i64.mul (i64.extend_i32_u (local.get $count)) (i64.const 60000)))
    (i64.add (local.get $ts_start)
      (i64.mul (i64.extend_i32_u (local.get $span_start)) (i64.const 60000)))
    (local.get $p)))))
(local.get $p))

;; ── JSON SCANNER HELPERS ─────────────────────────────────────────────────────

(func $find_pattern
(param $ptr i32) (param $end i32) (param $pat i32) (param $plen i32)
(result i32)
(local $j i32) (local $ok i32)
(block $found
(block $notfound
(loop $outer
  (br_if $notfound (i32.ge_u (local.get $ptr) (local.get $end)))
  (local.set $ok (i32.const 1))
  (local.set $j  (i32.const 0))
  (block $mismatch
    (loop $inner
      (br_if $mismatch (i32.eqz (local.get $ok)))
      (br_if $mismatch (i32.ge_u (local.get $j) (local.get $plen)))
      (if (i32.ne
            (i32.load8_u (i32.add (local.get $ptr) (local.get $j)))
            (i32.load8_u (i32.add (local.get $pat) (local.get $j))))
        (then (local.set $ok (i32.const 0))))
      (local.set $j (i32.add (local.get $j) (i32.const 1)))
      (br $inner)))
  (if (local.get $ok) (then (br $found)))
  (local.set $ptr (i32.add (local.get $ptr) (i32.const 1)))
  (br $outer)))
(return (local.get $end)))
(local.get $ptr))

(func $hex_nibble (param $c i32) (result i32)
(if (i32.and (i32.ge_u (local.get $c) (i32.const 48)) (i32.le_u (local.get $c) (i32.const 57)))
(then (return (i32.sub (local.get $c) (i32.const 48)))))
(if (i32.and (i32.ge_u (local.get $c) (i32.const 65)) (i32.le_u (local.get $c) (i32.const 70)))
(then (return (i32.sub (local.get $c) (i32.const 55)))))
(if (i32.and (i32.ge_u (local.get $c) (i32.const 97)) (i32.le_u (local.get $c) (i32.const 102)))
(then (return (i32.sub (local.get $c) (i32.const 87)))))
(i32.const 0xFF))

(func $scan_opcode (param $ptr i32) (param $end i32) (result i32)
(local $m i32) (local $hi i32) (local $lo i32)
(local.set $m (call $find_pattern (local.get $ptr) (local.get $end) (i32.const 0x40200) (i32.const 12)))
(if (i32.ge_u (local.get $m) (local.get $end)) (then (return (i32.const 0xFF))))
(local.set $m (i32.add (local.get $m) (i32.const 12)))
(if (i32.ge_u (i32.add (local.get $m) (i32.const 2)) (local.get $end))
(then (return (i32.const 0xFF))))
(local.set $hi (call $hex_nibble (i32.load8_u (local.get $m))))
(local.set $lo (call $hex_nibble (i32.load8_u (i32.add (local.get $m) (i32.const 1)))))
(if (i32.eq (local.get $hi) (i32.const 0xFF)) (then (return (i32.const 0xFF))))
(if (i32.eq (local.get $lo) (i32.const 0xFF)) (then (return (i32.const 0xFF))))
(i32.or (i32.shl (local.get $hi) (i32.const 4)) (local.get $lo)))

;; Writes b64ptr and b64len to i32 slots at 0xE040 and 0xE044.
(func $scan_bytes_field (param $ptr i32) (param $end i32)
(local $m i32) (local $len i32)
(local.set $m (call $find_pattern (local.get $ptr) (local.get $end) (i32.const 0x4020C) (i32.const 9)))
(if (i32.ge_u (local.get $m) (local.get $end))
(then
(i32.store (i32.const 0x40840) (i32.const 0))
(i32.store (i32.const 0x40844) (i32.const 0))
(return)))
(local.set $m (i32.add (local.get $m) (i32.const 9)))
(local.set $len (i32.const 0))
(block $done
(loop $L
(br_if $done (i32.ge_u (i32.add (local.get $m) (local.get $len)) (local.get $end)))
(br_if $done
  (i32.eq
    (i32.load8_u (i32.add (local.get $m) (local.get $len)))
    (i32.const 34)))
(local.set $len (i32.add (local.get $len) (i32.const 1)))
(br $L)))
(i32.store (i32.const 0x40840) (local.get $m))
(i32.store (i32.const 0x40844) (local.get $len)))

(func $find_frame_end (param $ptr i32) (param $end i32) (result i32)
(block $found
(loop $L
(br_if $found (i32.ge_u (local.get $ptr) (local.get $end)))
(br_if $found (i32.eq (i32.load8_u (local.get $ptr)) (i32.const 125)))
(local.set $ptr (i32.add (local.get $ptr) (i32.const 1)))
(br $L)))
(local.get $ptr))

(func $b64_decode (param $src i32) (param $srclen i32) (param $dst i32) (result i32)
(local $i i32) (local $j i32)
(local $a i32) (local $b i32) (local $c i32) (local $d i32)
(local $dslen i32) (local $rem i32)
(local.set $i (i32.const 0))
(local.set $j (i32.const 0))
;; strip trailing '=' to get effective unpadded length
(local.set $dslen (local.get $srclen))
(if (i32.gt_u (local.get $dslen) (i32.const 0))
(then
(if (i32.eq
      (i32.load8_u (i32.add (local.get $src) (i32.sub (local.get $dslen) (i32.const 1))))
      (i32.const 61))
  (then (local.set $dslen (i32.sub (local.get $dslen) (i32.const 1)))))))
(if (i32.gt_u (local.get $dslen) (i32.const 0))
(then
(if (i32.eq
      (i32.load8_u (i32.add (local.get $src) (i32.sub (local.get $dslen) (i32.const 1))))
      (i32.const 61))
  (then (local.set $dslen (i32.sub (local.get $dslen) (i32.const 1)))))))
;; rem: leftover chars after full 4-char groups (valid values: 0, 2, 3)
(local.set $rem (i32.and (local.get $dslen) (i32.const 3)))
(block $done
(loop $L
(br_if $done (i32.ge_u (local.get $i) (i32.and (local.get $dslen) (i32.const -4))))
(local.set $a (i32.load8_u (i32.add (i32.const 0x40100) (i32.load8_u (i32.add (local.get $src) (local.get $i))))))
(local.set $b (i32.load8_u (i32.add (i32.const 0x40100) (i32.load8_u (i32.add (local.get $src) (i32.add (local.get $i) (i32.const 1)))))))
(local.set $c (i32.load8_u (i32.add (i32.const 0x40100) (i32.load8_u (i32.add (local.get $src) (i32.add (local.get $i) (i32.const 2)))))))
(local.set $d (i32.load8_u (i32.add (i32.const 0x40100) (i32.load8_u (i32.add (local.get $src) (i32.add (local.get $i) (i32.const 3)))))))
(i32.store8 (i32.add (local.get $dst) (local.get $j))
  (i32.or (i32.shl (local.get $a) (i32.const 2)) (i32.shr_u (local.get $b) (i32.const 4))))
(i32.store8 (i32.add (local.get $dst) (i32.add (local.get $j) (i32.const 1)))
  (i32.or (i32.shl (i32.and (local.get $b) (i32.const 0x0F)) (i32.const 4)) (i32.shr_u (local.get $c) (i32.const 2))))
(i32.store8 (i32.add (local.get $dst) (i32.add (local.get $j) (i32.const 2)))
  (i32.or (i32.shl (i32.and (local.get $c) (i32.const 0x03)) (i32.const 6)) (local.get $d)))
(local.set $i (i32.add (local.get $i) (i32.const 4)))
(local.set $j (i32.add (local.get $j) (i32.const 3)))
(br $L)))
;; tail: rem==2 → 1 output byte; rem==3 → 2 output bytes
(if (i32.ge_u (local.get $rem) (i32.const 2))
(then
(local.set $a (i32.load8_u (i32.add (i32.const 0x40100) (i32.load8_u (i32.add (local.get $src) (local.get $i))))))
(local.set $b (i32.load8_u (i32.add (i32.const 0x40100) (i32.load8_u (i32.add (local.get $src) (i32.add (local.get $i) (i32.const 1)))))))
(i32.store8 (i32.add (local.get $dst) (local.get $j))
  (i32.or (i32.shl (local.get $a) (i32.const 2)) (i32.shr_u (local.get $b) (i32.const 4))))
(local.set $j (i32.add (local.get $j) (i32.const 1)))))
(if (i32.eq (local.get $rem) (i32.const 3))
(then
(local.set $c (i32.load8_u (i32.add (i32.const 0x40100) (i32.load8_u (i32.add (local.get $src) (i32.add (local.get $i) (i32.const 2)))))))
(i32.store8 (i32.add (local.get $dst) (local.get $j))
  (i32.or (i32.shl (i32.and (local.get $b) (i32.const 0x0F)) (i32.const 4)) (i32.shr_u (local.get $c) (i32.const 2))))
(local.set $j (i32.add (local.get $j) (i32.const 1)))))
(local.get $j))

;; ── parseSession ─────────────────────────────────────────────────────────────

(func $parseSession (param $framesPtr i32) (param $framesLen i32) (result i32)
(local $cur i32) (local $end i32)
(local $fend i32) (local $opcode i32)
(local $b64ptr i32) (local $b64len i32)
(local $rawlen i32)
(local $out i32) (local $out_len i32)

(global.set $need_comma (i32.const 0))
(local.set $out (i32.const 0x10000))
(local.set $out (call $wc (local.get $out) (i32.const 91)))  ;; '['

(local.set $cur (local.get $framesPtr))
(local.set $end (i32.add (local.get $framesPtr) (local.get $framesLen)))

;; Advance to first '{'
(block $skip_done
(loop $skip
(br_if $skip_done (i32.ge_u (local.get $cur) (local.get $end)))
(br_if $skip_done (i32.eq (i32.load8_u (local.get $cur)) (i32.const 123)))
(local.set $cur (i32.add (local.get $cur) (i32.const 1)))
(br $skip)))

(block $loop_done
(loop $frame_loop
(br_if $loop_done (i32.ge_u (local.get $cur) (local.get $end)))

(local.set $fend (call $find_frame_end (local.get $cur) (local.get $end)))
(local.set $opcode (call $scan_opcode (local.get $cur) (local.get $fend)))

(call $scan_bytes_field (local.get $cur) (local.get $fend))
(local.set $b64ptr (i32.load (i32.const 0x40840)))
(local.set $b64len (i32.load (i32.const 0x40844)))

(local.set $rawlen (i32.const 0))
(block $skip_oversized
  (br_if $skip_oversized
    (i32.gt_u
      (i32.div_u (i32.mul (local.get $b64len) (i32.const 3)) (i32.const 4))
      (i32.const 1024)))
  (if (i32.gt_u (local.get $b64len) (i32.const 0))
    (then
      (local.set $rawlen
        (call $b64_decode (local.get $b64ptr) (local.get $b64len) (i32.const 0x3FC00)))))

  (block $route

    (if (i32.eq (local.get $opcode) (i32.const 0x52))
      (then (local.set $out (call $do_steps (i32.const 0x3FC00) (local.get $out)))))
    (if (i32.eq (local.get $opcode) (i32.const 0x53))
      (then (local.set $out (call $do_sleep (i32.const 0x3FC00) (local.get $out)))))
    (if (i32.eq (local.get $opcode) (i32.const 0x55))
      (then (local.set $out (call $do_hr    (i32.const 0x3FC00) (local.get $out)))))
    (if (i32.eq (local.get $opcode) (i32.const 0x56))
      (then (local.set $out (call $do_hrv   (i32.const 0x3FC00) (local.get $rawlen) (local.get $out)))))
    (if (i32.eq (local.get $opcode) (i32.const 0x65))
      (then (local.set $out (call $do_temp  (i32.const 0x3FC00) (local.get $out)))))
    (if (i32.eq (local.get $opcode) (i32.const 0x66))
      (then (local.set $out (call $do_spo2  (i32.const 0x3FC00) (local.get $out)))))))

;; Advance to next '{'
(local.set $cur (i32.add (local.get $fend) (i32.const 1)))
(block $next_done
  (loop $next
    (br_if $next_done (i32.ge_u (local.get $cur) (local.get $end)))
    (br_if $next_done (i32.eq (i32.load8_u (local.get $cur)) (i32.const 123)))
    (local.set $cur (i32.add (local.get $cur) (i32.const 1)))
    (br $next)))

(br $frame_loop)))

(local.set $out (call $wc (local.get $out) (i32.const 93)))  ;; ']'
(local.set $out_len (i32.sub (local.get $out) (i32.const 0x10000)))
(drop (call $wmem (i32.const 0x1000) (i32.const 0x10000) (local.get $out_len)))
(local.get $out_len))

(export "parseSession" (func $parseSession))

;; ── buildSyncCommands ────────────────────────────────────────────────────────

(func $bcd_enc (param $v i32) (result i32)
(i32.or
(i32.shl (i32.div_u (local.get $v) (i32.const 10)) (i32.const 4))
(i32.rem_u (local.get $v) (i32.const 10))))

;; Decode epoch seconds to YMD HMS; stores 6 × i32 at $out_addr.
(func $epoch_s_to_ymd (param $epoch_s i64) (param $out_addr i32)
(local $days i64) (local $rem i64)
(local $y i32) (local $m i32) (local $d i32)
(local $h i32) (local $mi i32) (local $s i32)
(local $days_in_year i32)
(local $month_offset i32) (local $next_month_offset i32)

(local.set $days (i64.div_u (local.get $epoch_s) (i64.const 86400)))
(local.set $rem  (i64.rem_u (local.get $epoch_s) (i64.const 86400)))
(local.set $h  (i32.wrap_i64 (i64.div_u (local.get $rem) (i64.const 3600))))
(local.set $rem (i64.rem_u (local.get $rem) (i64.const 3600)))
(local.set $mi (i32.wrap_i64 (i64.div_u (local.get $rem) (i64.const 60))))
(local.set $s  (i32.wrap_i64 (i64.rem_u (local.get $rem) (i64.const 60))))

(local.set $y (i32.const 1970))
(block $ydone
(loop $yloop
(local.set $days_in_year (i32.const 365))
(if (call $leap (local.get $y))
  (then (local.set $days_in_year (i32.const 366))))
(br_if $ydone (i64.lt_u (local.get $days) (i64.extend_i32_u (local.get $days_in_year))))
(local.set $days (i64.sub (local.get $days) (i64.extend_i32_u (local.get $days_in_year))))
(local.set $y (i32.add (local.get $y) (i32.const 1)))
(br $yloop)))

(local.set $m (i32.const 1))
(block $mdone
(loop $mloop
(br_if $mdone (i32.gt_u (local.get $m) (i32.const 12)))
(local.set $month_offset
  (i32.load16_u
    (i32.add (i32.const 0x400E2)
      (i32.mul (i32.sub (local.get $m) (i32.const 1)) (i32.const 2)))))
(if (i32.lt_u (local.get $m) (i32.const 12))
  (then
    (local.set $next_month_offset
      (i32.load16_u
        (i32.add (i32.const 0x400E2)
          (i32.mul (local.get $m) (i32.const 2))))))
  (else
    (local.set $next_month_offset
      (i32.add (i32.const 365) (call $leap (local.get $y))))))
(br_if $mdone
  (i64.lt_u (local.get $days)
    (i64.extend_i32_u
      (i32.sub (local.get $next_month_offset) (local.get $month_offset)))))
(local.set $days
  (i64.sub (local.get $days)
    (i64.extend_i32_u
      (i32.sub (local.get $next_month_offset) (local.get $month_offset)))))
(local.set $m (i32.add (local.get $m) (i32.const 1)))
(br $mloop)))
(local.set $d (i32.add (i32.wrap_i64 (local.get $days)) (i32.const 1)))

(i32.store (local.get $out_addr)                           (local.get $y))
(i32.store (i32.add (local.get $out_addr) (i32.const 4))  (local.get $m))
(i32.store (i32.add (local.get $out_addr) (i32.const 8))  (local.get $d))
(i32.store (i32.add (local.get $out_addr) (i32.const 12)) (local.get $h))
(i32.store (i32.add (local.get $out_addr) (i32.const 16)) (local.get $mi))
(i32.store (i32.add (local.get $out_addr) (i32.const 20)) (local.get $s)))

;; Write hex byte as "0xNN " (5 chars), return ptr+5
(func $write_hex_byte (param $v i32) (param $p i32) (result i32)
(local $hi i32) (local $lo i32)
(local.set $hi (i32.shr_u (local.get $v) (i32.const 4)))
(local.set $lo (i32.and   (local.get $v) (i32.const 15)))
(local.set $p (call $wc (local.get $p) (i32.const 48)))
(local.set $p (call $wc (local.get $p) (i32.const 120)))
(if (i32.lt_u (local.get $hi) (i32.const 10))
(then (local.set $p (call $wc (local.get $p) (i32.add (i32.const 48) (local.get $hi)))))
(else (local.set $p (call $wc (local.get $p) (i32.add (i32.const 55) (local.get $hi))))))
(if (i32.lt_u (local.get $lo) (i32.const 10))
(then (local.set $p (call $wc (local.get $p) (i32.add (i32.const 48) (local.get $lo)))))
(else (local.set $p (call $wc (local.get $p) (i32.add (i32.const 55) (local.get $lo))))))
(call $wc (local.get $p) (i32.const 32)))

(func $scan_int_field
(param $ctx i32) (param $ctx_end i32)
(param $pat i32) (param $plen i32)
(param $default i32) (result i32)
(local $m i32) (local $v i32) (local $c i32)
(local.set $m (call $find_pattern (local.get $ctx) (local.get $ctx_end) (local.get $pat) (local.get $plen)))
(if (i32.ge_u (local.get $m) (local.get $ctx_end))
(then (return (local.get $default))))
(local.set $m (i32.add (local.get $m) (local.get $plen)))
(local.set $v (i32.const 0))
(block $done
(loop $L
(br_if $done (i32.ge_u (local.get $m) (local.get $ctx_end)))
(local.set $c (i32.load8_u (local.get $m)))
(br_if $done (i32.lt_u (local.get $c) (i32.const 48)))
(br_if $done (i32.gt_u (local.get $c) (i32.const 57)))
(local.set $v (i32.add (i32.mul (local.get $v) (i32.const 10)) (i32.sub (local.get $c) (i32.const 48))))
(local.set $m (i32.add (local.get $m) (i32.const 1)))
(br $L)))
(local.get $v))

(func $buildSyncCommands (param $ctxPtr i32) (param $ctxLen i32) (result i32)
(local $ctx_end i32)
(local $ts_ms i64) (local $utc_off i32) (local $local_s i64)
(local $y i32) (local $mo i32) (local $d i32)
(local $h i32) (local $mi i32) (local $s i32)
(local $tz_byte i32) (local $crc i32)
(local $sex i32) (local $age i32) (local $height i32) (local $weight i32) (local $stride i32)
(local $birth_year i32)
(local $p i32) (local $k i32) (local $m2 i32)

;; Read i64 LE from 0x0000
(local.set $ts_ms
(i64.or
(i64.or
  (i64.or
    (i64.or
      (i64.or
        (i64.or
          (i64.or
            (i64.extend_i32_u (i32.load8_u (i32.const 0)))
            (i64.shl (i64.extend_i32_u (i32.load8_u (i32.const 1))) (i64.const 8)))
          (i64.shl (i64.extend_i32_u (i32.load8_u (i32.const 2))) (i64.const 16)))
        (i64.shl (i64.extend_i32_u (i32.load8_u (i32.const 3))) (i64.const 24)))
      (i64.shl (i64.extend_i32_u (i32.load8_u (i32.const 4))) (i64.const 32)))
    (i64.shl (i64.extend_i32_u (i32.load8_u (i32.const 5))) (i64.const 40)))
  (i64.shl (i64.extend_i32_u (i32.load8_u (i32.const 6))) (i64.const 48)))
(i64.shl (i64.extend_i32_u (i32.load8_u (i32.const 7))) (i64.const 56))))

(local.set $utc_off
(i32.extend16_s
(i32.or
  (i32.load8_u (i32.const 8))
  (i32.shl (i32.load8_u (i32.const 9)) (i32.const 8)))))

(local.set $local_s
(i64.add
(i64.div_u (local.get $ts_ms) (i64.const 1000))
(i64.extend_i32_s (i32.mul (local.get $utc_off) (i32.const 60)))))

;; Decode to YMD at 0xE060
(call $epoch_s_to_ymd (local.get $local_s) (i32.const 0x40860))
(local.set $y  (i32.load (i32.const 0x40860)))
(local.set $mo (i32.load (i32.const 0x40864)))
(local.set $d  (i32.load (i32.const 0x40868)))
(local.set $h  (i32.load (i32.const 0x4086C)))
(local.set $mi (i32.load (i32.const 0x40870)))
(local.set $s  (i32.load (i32.const 0x40874)))

(local.set $ctx_end (i32.add (local.get $ctxPtr) (local.get $ctxLen)))

;; Sex: search for "biologicalSex":"male" (21 bytes at 0xDA15)
(local.set $sex (i32.const 0))
(if (i32.lt_u
  (call $find_pattern (local.get $ctxPtr) (local.get $ctx_end) (i32.const 0x40215) (i32.const 21))
  (local.get $ctx_end))
(then (local.set $sex (i32.const 1))))

;; Birth year from "dateOfBirth":"YYYY
(local.set $birth_year (i32.const 0))
(block $dob_done
(local.set $m2 (call $find_pattern (local.get $ctxPtr) (local.get $ctx_end) (i32.const 0x4022A) (i32.const 15)))
(br_if $dob_done (i32.ge_u (local.get $m2) (local.get $ctx_end)))
(local.set $m2 (i32.add (local.get $m2) (i32.const 15)))
(if (i32.ge_u (i32.add (local.get $m2) (i32.const 4)) (local.get $ctx_end))
(then (br $dob_done)))
(local.set $birth_year
(i32.add
  (i32.add
    (i32.add
      (i32.mul (i32.sub (i32.load8_u (local.get $m2))                          (i32.const 48)) (i32.const 1000))
      (i32.mul (i32.sub (i32.load8_u (i32.add (local.get $m2) (i32.const 1))) (i32.const 48)) (i32.const 100)))
    (i32.mul (i32.sub (i32.load8_u (i32.add (local.get $m2) (i32.const 2))) (i32.const 48)) (i32.const 10)))
  (i32.sub (i32.load8_u (i32.add (local.get $m2) (i32.const 3))) (i32.const 48)))))
(local.set $age (i32.const 30))
(if (i32.gt_u (local.get $birth_year) (i32.const 1900))
(then (local.set $age (i32.sub (local.get $y) (local.get $birth_year)))))

(local.set $height (call $scan_int_field (local.get $ctxPtr) (local.get $ctx_end) (i32.const 0x40239) (i32.const 11) (i32.const 170)))
(local.set $weight (call $scan_int_field (local.get $ctxPtr) (local.get $ctx_end) (i32.const 0x40244) (i32.const 11) (i32.const 70)))
(local.set $stride (call $scan_int_field (local.get $ctxPtr) (local.get $ctx_end) (i32.const 0x4024F) (i32.const 17) (i32.const 70)))

;; Timezone byte: +offset_hours+128, or abs(offset_hours) for negative
(local.set $tz_byte
(if (result i32) (i32.ge_s (local.get $utc_off) (i32.const 0))
(then (i32.add (i32.div_s (local.get $utc_off) (i32.const 60)) (i32.const 128)))
(else (i32.div_u (i32.sub (i32.const 0) (local.get $utc_off)) (i32.const 60)))))

;; SetDeviceTime (0x01) raw bytes at 0xE080
(i32.store8 (i32.const 0x40880) (i32.const 0x01))
(i32.store8 (i32.const 0x40881) (call $bcd_enc (i32.rem_u (local.get $y) (i32.const 100))))
(i32.store8 (i32.const 0x40882) (call $bcd_enc (local.get $mo)))
(i32.store8 (i32.const 0x40883) (call $bcd_enc (local.get $d)))
(i32.store8 (i32.const 0x40884) (call $bcd_enc (local.get $h)))
(i32.store8 (i32.const 0x40885) (call $bcd_enc (local.get $mi)))
(i32.store8 (i32.const 0x40886) (call $bcd_enc (local.get $s)))
(i32.store8 (i32.const 0x40887) (i32.const 0x00))
(i32.store8 (i32.const 0x40888) (local.get $tz_byte))
(i32.store8 (i32.const 0x40889) (i32.const 0x00))
(i32.store8 (i32.const 0x4088A) (i32.const 0x00))
(i32.store8 (i32.const 0x4088B) (i32.const 0x00))
(i32.store8 (i32.const 0x4088C) (i32.const 0x00))
(i32.store8 (i32.const 0x4088D) (i32.const 0x00))
(i32.store8 (i32.const 0x4088E) (i32.const 0x00))
(local.set $crc (i32.const 0))
(local.set $k (i32.const 0))
(block $c1d
(loop $c1
(br_if $c1d (i32.ge_u (local.get $k) (i32.const 15)))
(local.set $crc (i32.add (local.get $crc) (i32.load8_u (i32.add (i32.const 0x40880) (local.get $k)))))
(local.set $k (i32.add (local.get $k) (i32.const 1)))
(br $c1)))
(i32.store8 (i32.const 0x4088F) (i32.and (local.get $crc) (i32.const 0xFF)))

;; SetPersonalInfo (0x02) raw bytes at 0xE090
(i32.store8 (i32.const 0x40890) (i32.const 0x02))
(i32.store8 (i32.const 0x40891) (local.get $sex))
(i32.store8 (i32.const 0x40892) (local.get $age))
(i32.store8 (i32.const 0x40893) (i32.and (local.get $height) (i32.const 0xFF)))
(i32.store8 (i32.const 0x40894) (i32.and (local.get $weight) (i32.const 0xFF)))
(i32.store8 (i32.const 0x40895) (i32.and (local.get $stride) (i32.const 0xFF)))
(i32.store8 (i32.const 0x40896) (i32.const 0x00))
(i32.store8 (i32.const 0x40897) (i32.const 0x00))
(i32.store8 (i32.const 0x40898) (i32.const 0x00))
(i32.store8 (i32.const 0x40899) (i32.const 0x00))
(i32.store8 (i32.const 0x4089A) (i32.const 0x00))
(i32.store8 (i32.const 0x4089B) (i32.const 0x00))
(i32.store8 (i32.const 0x4089C) (i32.const 0x00))
(i32.store8 (i32.const 0x4089D) (i32.const 0x00))
(i32.store8 (i32.const 0x4089E) (i32.const 0x00))
(local.set $crc (i32.const 0))
(local.set $k (i32.const 0))
(block $c2d
(loop $c2
(br_if $c2d (i32.ge_u (local.get $k) (i32.const 15)))
(local.set $crc (i32.add (local.get $crc) (i32.load8_u (i32.add (i32.const 0x40890) (local.get $k)))))
(local.set $k (i32.add (local.get $k) (i32.const 1)))
(br $c2)))
(i32.store8 (i32.const 0x4089F) (i32.and (local.get $crc) (i32.const 0xFF)))

;; Serialise to JSON at CMD_OUT_OFFSET = 0x0400
(local.set $p (i32.const 0x0400))
;; [DD00] 51 bytes: [{"type":"ENABLE_NOTIFY","characteristic":"notify"}
(local.set $p (call $wmem (local.get $p) (i32.const 0x40500) (i32.const 51)))
;; [DD33] 194 bytes: ,{0x13 handshake with awaitReply}
(local.set $p (call $wmem (local.get $p) (i32.const 0x40533) (i32.const 194)))
;; [DB73] 51 bytes: ,{"type":"WRITE","characteristic":"write","bytes":"
(local.set $p (call $wmem (local.get $p) (i32.const 0x40373) (i32.const 51)))
(local.set $k (i32.const 0))
(block $h1d
(loop $h1
(br_if $h1d (i32.ge_u (local.get $k) (i32.const 16)))
(local.set $p (call $write_hex_byte (i32.load8_u (i32.add (i32.const 0x40880) (local.get $k))) (local.get $p)))
(local.set $k (i32.add (local.get $k) (i32.const 1)))
(br $h1)))
;; Remove trailing space
(local.set $p (i32.sub (local.get $p) (i32.const 1)))
;; [DB33] 64 bytes: ","awaitReply":{...}}  (no ])
(local.set $p (call $wmem (local.get $p) (i32.const 0x40333) (i32.const 64)))
;; [DB73] 51 bytes: ,{"type":"WRITE","characteristic":"write","bytes":"
(local.set $p (call $wmem (local.get $p) (i32.const 0x40373) (i32.const 51)))
(local.set $k (i32.const 0))
(block $h2d
(loop $h2
(br_if $h2d (i32.ge_u (local.get $k) (i32.const 16)))
(local.set $p (call $write_hex_byte (i32.load8_u (i32.add (i32.const 0x40890) (local.get $k))) (local.get $p)))
(local.set $k (i32.add (local.get $k) (i32.const 1)))
(br $h2)))
(local.set $p (i32.sub (local.get $p) (i32.const 1)))
;; [DB33] 64 bytes: ","awaitReply":{...}}  (no ]) — reuse same template
(local.set $p (call $wmem (local.get $p) (i32.const 0x40333) (i32.const 64)))
;; 0x55 HR fetch
(local.set $p (call $wmem (local.get $p) (i32.const 0x40373) (i32.const 51)))
(local.set $p (call $wmem (local.get $p) (i32.const 0x4065B) (i32.const 79)))
(local.set $p (call $wmem (local.get $p) (i32.const 0x405F5) (i32.const 102)))
;; 0x52 HR/SpO2 fetch
(local.set $p (call $wmem (local.get $p) (i32.const 0x40373) (i32.const 51)))
(local.set $p (call $wmem (local.get $p) (i32.const 0x406AA) (i32.const 79)))
(local.set $p (call $wmem (local.get $p) (i32.const 0x405F5) (i32.const 102)))
;; 0x53 Steps fetch
(local.set $p (call $wmem (local.get $p) (i32.const 0x40373) (i32.const 51)))
(local.set $p (call $wmem (local.get $p) (i32.const 0x406F9) (i32.const 79)))
(local.set $p (call $wmem (local.get $p) (i32.const 0x405F5) (i32.const 102)))
;; 0x66 SpO2 fetch
(local.set $p (call $wmem (local.get $p) (i32.const 0x40373) (i32.const 51)))
(local.set $p (call $wmem (local.get $p) (i32.const 0x40748) (i32.const 79)))
(local.set $p (call $wmem (local.get $p) (i32.const 0x405F5) (i32.const 102)))
;; 0x56 HRV/BP fetch
(local.set $p (call $wmem (local.get $p) (i32.const 0x40373) (i32.const 51)))
(local.set $p (call $wmem (local.get $p) (i32.const 0x40797) (i32.const 79)))
(local.set $p (call $wmem (local.get $p) (i32.const 0x405F5) (i32.const 102)))
;; 0x65 Skin temp fetch
(local.set $p (call $wmem (local.get $p) (i32.const 0x40373) (i32.const 51)))
(local.set $p (call $wmem (local.get $p) (i32.const 0x407E6) (i32.const 79)))
(local.set $p (call $wmem (local.get $p) (i32.const 0x405F5) (i32.const 102)))
;; ']'
(local.set $p (call $wc (local.get $p) (i32.const 93)))

(i32.sub (local.get $p) (i32.const 0x0400)))

(export "buildSyncCommands" (func $buildSyncCommands))
)
