(module
  ;; 2 pages (128 KB).
  ;; 0x0000-0x00FF  input payload (engine writes BLE bytes here)
  ;; 0x0100-0x015F  scratch for int-to-string reversal
  ;; 0x0800-0x08FF  static string/table constants
  ;; 0x1000-0xFFFF  output region (we write JSON here, return byte count)
  (memory (export "memory") 2)

  ;; ── STATIC DATA ─────────────────────────────────────────────────────────────

  (data (i32.const 0x0800)
    ;; [0x0800] len=15  {"metricType":"
    "\7b\22\6d\65\74\72\69\63\54\79\70\65\22\3a\22"
    ;; [0x080f] len=10  ","value":
    "\22\2c\22\76\61\6c\75\65\22\3a"
    ;; [0x0819] len=9   ,"unit":"
    "\2c\22\75\6e\69\74\22\3a\22"
    ;; [0x0822] len=17  ","recordedAtMs":
    "\22\2c\22\72\65\63\6f\72\64\65\64\41\74\4d\73\22\3a"
    ;; [0x0833] MetricType name strings (no quotes)
    "HR"         ;; [0x0833] len=2
    "HRV"        ;; [0x0835] len=3
    "SPO2"       ;; [0x0838] len=4
    "STEPS"      ;; [0x083c] len=5
    "BATTERY"    ;; [0x0841] len=7
    "SKIN_TEMP"  ;; [0x0848] len=9
    "CALORIES"   ;; [0x0851] len=8
    "DISTANCE"   ;; [0x0859] len=8
    ;; [0x0861] Unit strings
    "bpm"        ;; [0x0861] len=3
    "ms"         ;; [0x0864] len=2
    "%"          ;; [0x0866] len=1
    "steps"      ;; [0x0867] len=5
    "kcal"       ;; [0x086c] len=4
    "m"          ;; [0x0870] len=1
    "\c2\b0\43"  ;; [0x0871] "°C" UTF-8, len=3
  )

  ;; [0x0880] Month-day offsets (12 x i16 LE): Jan=0, Feb=31, ..., Dec=334
  (data (i32.const 0x0880)
    "\00\00\1f\00\3b\00\5a\00\78\00\97\00\b5\00\d4\00\f3\00\11\01\30\01\4e\01"
  )

  ;; [0x0898] Sleep JSON key fragments
  (data (i32.const 0x0898)
    ;; [0x0898] len=12  {"dateIso":"
    "\7b\22\64\61\74\65\49\73\6f\22\3a\22"
    ;; [0x08a4] len=17  ","sleepStartMs":
    "\22\2c\22\73\6c\65\65\70\53\74\61\72\74\4d\73\22\3a"
    ;; [0x08b5] len=14  ,"sleepEndMs":
    "\2c\22\73\6c\65\65\70\45\6e\64\4d\73\22\3a"
    ;; [0x08c3] len=19  ,"durationMinutes":
    "\2c\22\64\75\72\61\74\69\6f\6e\4d\69\6e\75\74\65\73\22\3a"
    ;; [0x08d6] len=16  ,"stagesJson":"[
    "\2c\22\73\74\61\67\65\73\4a\73\6f\6e\22\3a\22\5b"
    ;; [0x08e6] len=3   ]"}
    "\5d\22\7d"
  )

  ;; ── UTILITY HELPERS ─────────────────────────────────────────────────────────

  ;; Decode one BCD byte: 0x26 → 26
  (func $bcd (param $b i32) (result i32)
    (i32.add
      (i32.mul
        (i32.and (i32.shr_u (local.get $b) (i32.const 4)) (i32.const 15))
        (i32.const 10))
      (i32.and (local.get $b) (i32.const 15))))

  ;; Gregorian leap year (correct for 1970-2099)
  (func $leap (param $y i32) (result i32)
    (i32.or
      (i32.and
        (i32.eqz (i32.rem_u (local.get $y) (i32.const 4)))
        (i32.ne  (i32.rem_u (local.get $y) (i32.const 100)) (i32.const 0)))
      (i32.eqz (i32.rem_u (local.get $y) (i32.const 400)))))

  ;; Days from 1970-01-01 to the start of the given calendar date
  (func $days_epoch (param $y i32) (param $m i32) (param $d i32) (result i32)
    (local $days i32)
    ;; years × 365
    (local.set $days (i32.mul (i32.sub (local.get $y) (i32.const 1970)) (i32.const 365)))
    ;; leap days from 1970 to y-1:  floor((y - 1969) / 4)
    (local.set $days (i32.add (local.get $days)
      (i32.div_u (i32.sub (local.get $y) (i32.const 1969)) (i32.const 4))))
    ;; month offset from table at 0x0880 (1-indexed)
    (local.set $days (i32.add (local.get $days)
      (i32.load16_u
        (i32.add (i32.const 0x0880)
          (i32.mul (i32.sub (local.get $m) (i32.const 1)) (i32.const 2))))))
    ;; extra day if leap year and past February
    (if (i32.and (call $leap (local.get $y)) (i32.gt_u (local.get $m) (i32.const 2)))
      (then (local.set $days (i32.add (local.get $days) (i32.const 1)))))
    ;; day offset (1-based)
    (i32.add (local.get $days) (i32.sub (local.get $d) (i32.const 1))))

  ;; Read 6 BCD bytes at mem[base..base+5] = yy mm dd hh mm ss, return epoch ms (i64).
  ;; Year is 2000 + yy.
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

  ;; Little-endian 16-bit read
  (func $le16 (param $base i32) (result i32)
    (i32.or
      (i32.load8_u (local.get $base))
      (i32.shl (i32.load8_u (i32.add (local.get $base) (i32.const 1))) (i32.const 8))))

  ;; ── WRITERS ─────────────────────────────────────────────────────────────────

  ;; Write one ASCII byte to ptr, return ptr+1
  (func $wc (param $ptr i32) (param $ch i32) (result i32)
    (i32.store8 (local.get $ptr) (local.get $ch))
    (i32.add (local.get $ptr) (i32.const 1)))

  ;; Copy n bytes from src to dst (simple loop; avoids bulk-memory dependency),
  ;; return dst+n
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

  ;; Write unsigned 32-bit integer as decimal to ptr, return new ptr.
  ;; Uses scratch at 0x0100-0x010F.
  (func $write_u32 (param $v i32) (param $p i32) (result i32)
    (local $lo i32) (local $hi i32) (local $len i32) (local $c i32)
    (if (i32.eqz (local.get $v))
      (then (return (call $wc (local.get $p) (i32.const 48)))))
    (local.set $lo (i32.const 0x0100))
    (local.set $hi (i32.const 0x0100))
    (block $done
      (loop $L
        (br_if $done (i32.eqz (local.get $v)))
        (i32.store8 (local.get $hi)
          (i32.add (i32.const 48) (i32.rem_u (local.get $v) (i32.const 10))))
        (local.set $hi (i32.add (local.get $hi) (i32.const 1)))
        (local.set $v  (i32.div_u (local.get $v) (i32.const 10)))
        (br $L)))
    (local.set $len (i32.sub (local.get $hi) (i32.const 0x0100)))
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
    (call $wmem (local.get $p) (i32.const 0x0100) (local.get $len)))

  ;; Write unsigned 64-bit integer as decimal to ptr, return new ptr.
  ;; Uses scratch at 0x0120-0x0133 (max 20 digits).
  (func $write_u64 (param $v i64) (param $p i32) (result i32)
    (local $lo i32) (local $hi i32) (local $len i32) (local $c i32)
    (if (i64.eqz (local.get $v))
      (then (return (call $wc (local.get $p) (i32.const 48)))))
    (local.set $lo (i32.const 0x0120))
    (local.set $hi (i32.const 0x0120))
    (block $done
      (loop $L
        (br_if $done (i64.eqz (local.get $v)))
        (i32.store8 (local.get $hi)
          (i32.add (i32.const 48) (i32.wrap_i64 (i64.rem_u (local.get $v) (i64.const 10)))))
        (local.set $hi (i32.add (local.get $hi) (i32.const 1)))
        (local.set $v  (i64.div_u (local.get $v) (i64.const 10)))
        (br $L)))
    (local.set $len (i32.sub (local.get $hi) (i32.const 0x0120)))
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
    (call $wmem (local.get $p) (i32.const 0x0120) (local.get $len)))

  ;; Write raw/10 as "X.Y"  e.g. 274 → "27.4"
  (func $write_1dp (param $v i32) (param $p i32) (result i32)
    (local.set $p (call $write_u32 (i32.div_u (local.get $v) (i32.const 10)) (local.get $p)))
    (local.set $p (call $wc (local.get $p) (i32.const 46)))
    (call $wc (local.get $p)
      (i32.add (i32.const 48) (i32.rem_u (local.get $v) (i32.const 10)))))

  ;; Write raw/100 as "X.YZ"  e.g. 1234 → "12.34"
  (func $write_2dp (param $v i32) (param $p i32) (result i32)
    (local $frac i32) (local $p2 i32)
    (local.set $frac (i32.rem_u (local.get $v) (i32.const 100)))
    (local.set $p2   (call $write_u32 (i32.div_u (local.get $v) (i32.const 100)) (local.get $p)))
    (local.set $p2   (call $wc (local.get $p2) (i32.const 46)))
    (if (i32.lt_u (local.get $frac) (i32.const 10))
      (then (local.set $p2 (call $wc (local.get $p2) (i32.const 48)))))
    (call $write_u32 (local.get $frac) (local.get $p2)))

  ;; Write "YYYY-MM-DD" (always zero-padded month and day)
  (func $write_date (param $y i32) (param $m i32) (param $d i32) (param $p i32) (result i32)
    (local.set $p (call $write_u32 (local.get $y) (local.get $p)))
    (local.set $p (call $wc (local.get $p) (i32.const 45)))
    (if (i32.lt_u (local.get $m) (i32.const 10))
      (then (local.set $p (call $wc (local.get $p) (i32.const 48)))))
    (local.set $p (call $write_u32 (local.get $m) (local.get $p)))
    (local.set $p (call $wc (local.get $p) (i32.const 45)))
    (if (i32.lt_u (local.get $d) (i32.const 10))
      (then (local.set $p (call $wc (local.get $p) (i32.const 48)))))
    (call $write_u32 (local.get $d) (local.get $p)))

  ;; ── JSON METRIC EMITTERS ─────────────────────────────────────────────────────
  ;;
  ;; Each emit_* writes one complete metric JSON object to ptr and returns new ptr.
  ;; The caller is responsible for surrounding '[', ']', and ',' separators.

  ;; Emit {"metricType":"<type>","value":<int>,"unit":"<unit>","recordedAtMs":<ts>}
  (func $emit_int
      (param $tp i32) (param $tl i32)   ;; type string ptr + len
      (param $up i32) (param $ul i32)   ;; unit string ptr + len
      (param $val i32) (param $ts i64)
      (param $p i32) (result i32)
    (local.set $p (call $wmem (local.get $p) (i32.const 0x0800) (i32.const 15))) ;; {"metricType":"
    (local.set $p (call $wmem (local.get $p) (local.get $tp) (local.get $tl)))
    (local.set $p (call $wmem (local.get $p) (i32.const 0x080f) (i32.const 10))) ;; ","value":
    (local.set $p (call $write_u32 (local.get $val) (local.get $p)))
    (local.set $p (call $wmem (local.get $p) (i32.const 0x0819) (i32.const 9)))  ;; ,"unit":"
    (local.set $p (call $wmem (local.get $p) (local.get $up) (local.get $ul)))
    (local.set $p (call $wmem (local.get $p) (i32.const 0x0822) (i32.const 17))) ;; ","recordedAtMs":
    (local.set $p (call $write_u64 (local.get $ts) (local.get $p)))
    (call $wc (local.get $p) (i32.const 125))) ;; }

  ;; Emit with value formatted as X.Y  (raw/10)
  (func $emit_1dp
      (param $tp i32) (param $tl i32)
      (param $up i32) (param $ul i32)
      (param $val i32) (param $ts i64)
      (param $p i32) (result i32)
    (local.set $p (call $wmem (local.get $p) (i32.const 0x0800) (i32.const 15)))
    (local.set $p (call $wmem (local.get $p) (local.get $tp) (local.get $tl)))
    (local.set $p (call $wmem (local.get $p) (i32.const 0x080f) (i32.const 10)))
    (local.set $p (call $write_1dp (local.get $val) (local.get $p)))
    (local.set $p (call $wmem (local.get $p) (i32.const 0x0819) (i32.const 9)))
    (local.set $p (call $wmem (local.get $p) (local.get $up) (local.get $ul)))
    (local.set $p (call $wmem (local.get $p) (i32.const 0x0822) (i32.const 17)))
    (local.set $p (call $write_u64 (local.get $ts) (local.get $p)))
    (call $wc (local.get $p) (i32.const 125)))

  ;; Emit with value formatted as X.YZ  (raw/100)
  (func $emit_2dp
      (param $tp i32) (param $tl i32)
      (param $up i32) (param $ul i32)
      (param $val i32) (param $ts i64)
      (param $p i32) (result i32)
    (local.set $p (call $wmem (local.get $p) (i32.const 0x0800) (i32.const 15)))
    (local.set $p (call $wmem (local.get $p) (local.get $tp) (local.get $tl)))
    (local.set $p (call $wmem (local.get $p) (i32.const 0x080f) (i32.const 10)))
    (local.set $p (call $write_2dp (local.get $val) (local.get $p)))
    (local.set $p (call $wmem (local.get $p) (i32.const 0x0819) (i32.const 9)))
    (local.set $p (call $wmem (local.get $p) (local.get $up) (local.get $ul)))
    (local.set $p (call $wmem (local.get $p) (i32.const 0x0822) (i32.const 17)))
    (local.set $p (call $write_u64 (local.get $ts) (local.get $p)))
    (call $wc (local.get $p) (i32.const 125)))

  ;; ── OPCODE HANDLERS ──────────────────────────────────────────────────────────
  ;; Each handler writes a complete JSON array starting at $p and returns end ptr.
  ;; Returns $p unchanged (no output) for end-marker packets.

  ;; 0x13 — Battery level: byte[1] = %
  (func $do_battery (param $p i32) (result i32)
    (local $end i32)
    (local.set $end (call $wc (local.get $p) (i32.const 91)))  ;; [
    (local.set $end
      (call $emit_int
        (i32.const 0x0841) (i32.const 7)   ;; "BATTERY"
        (i32.const 0x0866) (i32.const 1)   ;; "%"
        (i32.load8_u (i32.const 1))         ;; battery %
        (i64.const 0)                        ;; no timestamp on battery
        (local.get $end)))
    (call $wc (local.get $end) (i32.const 93)))  ;; ]

  ;; 0x55 — HR history record: bytes[3..8]=BCD ts, byte[9]=bpm
  (func $do_hr (param $p i32) (result i32)
    (local $end i32)
    (if (i32.eq (i32.load8_u (i32.const 1)) (i32.const 0xff))
      (then (return (local.get $p))))
    (local.set $end (call $wc (local.get $p) (i32.const 91)))
    (local.set $end
      (call $emit_int
        (i32.const 0x0833) (i32.const 2)   ;; "HR"
        (i32.const 0x0861) (i32.const 3)   ;; "bpm"
        (i32.load8_u (i32.const 9))
        (call $bcd_ts (i32.const 3))
        (local.get $end)))
    (call $wc (local.get $end) (i32.const 93)))

  ;; 0x52 — Intraday activity: bytes[3..8]=ts, [9..10]=steps, [11..12]=cal×100, [13..14]=km×100
  (func $do_steps (param $p i32) (result i32)
    (local $end i32) (local $ts i64)
    (if (i32.eq (i32.load8_u (i32.const 1)) (i32.const 0xff))
      (then (return (local.get $p))))
    (local.set $ts (call $bcd_ts (i32.const 3)))
    (local.set $end (call $wc (local.get $p) (i32.const 91)))
    ;; STEPS
    (local.set $end
      (call $emit_int
        (i32.const 0x083c) (i32.const 5)   ;; "STEPS"
        (i32.const 0x0867) (i32.const 5)   ;; "steps"
        (call $le16 (i32.const 9))
        (local.get $ts)
        (local.get $end)))
    (local.set $end (call $wc (local.get $end) (i32.const 44)))  ;; ,
    ;; CALORIES  (raw/100 kcal)
    (local.set $end
      (call $emit_2dp
        (i32.const 0x0851) (i32.const 8)   ;; "CALORIES"
        (i32.const 0x086c) (i32.const 4)   ;; "kcal"
        (call $le16 (i32.const 11))
        (local.get $ts)
        (local.get $end)))
    (local.set $end (call $wc (local.get $end) (i32.const 44)))  ;; ,
    ;; DISTANCE  (raw km×100 → metres = raw×10)
    (local.set $end
      (call $emit_int
        (i32.const 0x0859) (i32.const 8)   ;; "DISTANCE"
        (i32.const 0x0870) (i32.const 1)   ;; "m"
        (i32.mul (call $le16 (i32.const 13)) (i32.const 10))
        (local.get $ts)
        (local.get $end)))
    (call $wc (local.get $end) (i32.const 93)))

  ;; 0x56 — HRV session: bytes[3..8]=ts, [9]=HRV ms, [11]=HR bpm, [12]=stress score
  (func $do_hrv (param $p i32) (result i32)
    (local $end i32) (local $ts i64)
    (if (i32.eq (i32.load8_u (i32.const 1)) (i32.const 0xff))
      (then (return (local.get $p))))
    (local.set $ts (call $bcd_ts (i32.const 3)))
    (local.set $end (call $wc (local.get $p) (i32.const 91)))
    ;; HRV
    (local.set $end
      (call $emit_int
        (i32.const 0x0835) (i32.const 3)   ;; "HRV"
        (i32.const 0x0864) (i32.const 2)   ;; "ms"
        (i32.load8_u (i32.const 9))
        (local.get $ts)
        (local.get $end)))
    (local.set $end (call $wc (local.get $end) (i32.const 44)))  ;; ,
    ;; HR from HRV session
    (local.set $end
      (call $emit_int
        (i32.const 0x0833) (i32.const 2)   ;; "HR"
        (i32.const 0x0861) (i32.const 3)   ;; "bpm"
        (i32.load8_u (i32.const 11))
        (local.get $ts)
        (local.get $end)))
    (call $wc (local.get $end) (i32.const 93)))

  ;; 0x65 — Temperature: bytes[3..8]=ts, [9..10]=LE16 raw (raw/10 = °C)
  (func $do_temp (param $p i32) (result i32)
    (local $end i32)
    (if (i32.eq (i32.load8_u (i32.const 1)) (i32.const 0xff))
      (then (return (local.get $p))))
    (local.set $end (call $wc (local.get $p) (i32.const 91)))
    (local.set $end
      (call $emit_1dp
        (i32.const 0x0848) (i32.const 9)   ;; "SKIN_TEMP"
        (i32.const 0x0871) (i32.const 3)   ;; "°C"
        (call $le16 (i32.const 9))
        (call $bcd_ts (i32.const 3))
        (local.get $end)))
    (call $wc (local.get $end) (i32.const 93)))

  ;; 0x66 — SpO2: bytes[3..8]=ts, byte[9]=%
  (func $do_spo2 (param $p i32) (result i32)
    (local $end i32)
    (if (i32.eq (i32.load8_u (i32.const 1)) (i32.const 0xff))
      (then (return (local.get $p))))
    (local.set $end (call $wc (local.get $p) (i32.const 91)))
    (local.set $end
      (call $emit_int
        (i32.const 0x0838) (i32.const 4)   ;; "SPO2"
        (i32.const 0x0866) (i32.const 1)   ;; "%"
        (i32.load8_u (i32.const 9))
        (call $bcd_ts (i32.const 3))
        (local.get $end)))
    (call $wc (local.get $end) (i32.const 93)))

  ;; ── EXPORTS ──────────────────────────────────────────────────────────────────

  (func (export "parseMetrics") (param $offset i32) (param $len i32) (result i32)
    (local $opcode i32)
    (local $end i32)
    (if (i32.lt_u (local.get $len) (i32.const 1)) (then (return (i32.const 0))))
    (local.set $opcode (i32.load8_u (i32.const 0)))
    ;; Default: no output (end marker, ack, or unknown opcode)
    (local.set $end (i32.const 0x1000))
    (if (i32.eq (local.get $opcode) (i32.const 0x13))
      (then (local.set $end (call $do_battery (i32.const 0x1000)))))
    (if (i32.eq (local.get $opcode) (i32.const 0x55))
      (then (local.set $end (call $do_hr      (i32.const 0x1000)))))
    (if (i32.eq (local.get $opcode) (i32.const 0x52))
      (then (local.set $end (call $do_steps   (i32.const 0x1000)))))
    (if (i32.eq (local.get $opcode) (i32.const 0x56))
      (then (local.set $end (call $do_hrv     (i32.const 0x1000)))))
    (if (i32.eq (local.get $opcode) (i32.const 0x65))
      (then (local.set $end (call $do_temp    (i32.const 0x1000)))))
    (if (i32.eq (local.get $opcode) (i32.const 0x66))
      (then (local.set $end (call $do_spo2    (i32.const 0x1000)))))
    (i32.sub (local.get $end) (i32.const 0x1000)))

  ;; parseSleep: handles 0x53 sleep session records.
  ;; Protocol: [0]=0x53, [1..2]=index LE16, [3..8]=BCD start ts,
  ;;            [9]=stage count, [10..10+count-1]=stage bytes (1=wake 2=light 3=deep 5=REM)
  (func (export "parseSleep") (param $offset i32) (param $len i32) (result i32)
    (local $y i32) (local $mo i32) (local $d i32)
    (local $h i32) (local $mi i32) (local $s i32)
    (local $ts_start i64) (local $ts_end i64)
    (local $stages i32) (local $i i32)
    (local $p i32)

    (if (i32.lt_u (local.get $len) (i32.const 10)) (then (return (i32.const 0))))
    (if (i32.ne (i32.load8_u (i32.const 0)) (i32.const 0x53)) (then (return (i32.const 0))))
    (if (i32.eq (i32.load8_u (i32.const 1)) (i32.const 0xff)) (then (return (i32.const 0))))

    ;; Decode BCD timestamp components for re-use in date string and epoch calc
    (local.set $y  (i32.add (call $bcd (i32.load8_u (i32.const 3))) (i32.const 2000)))
    (local.set $mo (call $bcd (i32.load8_u (i32.const 4))))
    (local.set $d  (call $bcd (i32.load8_u (i32.const 5))))
    (local.set $h  (call $bcd (i32.load8_u (i32.const 6))))
    (local.set $mi (call $bcd (i32.load8_u (i32.const 7))))
    (local.set $s  (call $bcd (i32.load8_u (i32.const 8))))

    (local.set $ts_start
      (i64.mul
        (i64.add
          (i64.add
            (i64.add
              (i64.mul (i64.extend_i32_u (call $days_epoch (local.get $y) (local.get $mo) (local.get $d)))
                       (i64.const 86400))
              (i64.mul (i64.extend_i32_u (local.get $h))  (i64.const 3600)))
            (i64.mul (i64.extend_i32_u (local.get $mi)) (i64.const 60)))
          (i64.extend_i32_u (local.get $s)))
        (i64.const 1000)))

    (local.set $stages (i32.load8_u (i32.const 9)))
    (local.set $ts_end
      (i64.add (local.get $ts_start)
        (i64.mul (i64.extend_i32_u (local.get $stages)) (i64.const 60000))))

    (local.set $p (i32.const 0x1000))

    ;; {"dateIso":"
    (local.set $p (call $wmem (local.get $p) (i32.const 0x0898) (i32.const 12)))
    (local.set $p (call $write_date (local.get $y) (local.get $mo) (local.get $d) (local.get $p)))
    ;; ","sleepStartMs":
    (local.set $p (call $wmem (local.get $p) (i32.const 0x08a4) (i32.const 17)))
    (local.set $p (call $write_u64 (local.get $ts_start) (local.get $p)))
    ;; ,"sleepEndMs":
    (local.set $p (call $wmem (local.get $p) (i32.const 0x08b5) (i32.const 14)))
    (local.set $p (call $write_u64 (local.get $ts_end) (local.get $p)))
    ;; ,"durationMinutes":
    (local.set $p (call $wmem (local.get $p) (i32.const 0x08c3) (i32.const 19)))
    (local.set $p (call $write_u32 (local.get $stages) (local.get $p)))
    ;; ,"stagesJson":"[
    (local.set $p (call $wmem (local.get $p) (i32.const 0x08d6) (i32.const 16)))
    ;; stage bytes as comma-separated integers
    (local.set $i (i32.const 0))
    (block $sdone
      (loop $SL
        (br_if $sdone (i32.ge_u (local.get $i) (local.get $stages)))
        (if (i32.gt_u (local.get $i) (i32.const 0))
          (then (local.set $p (call $wc (local.get $p) (i32.const 44)))))
        (local.set $p
          (call $write_u32
            (i32.load8_u (i32.add (i32.const 10) (local.get $i)))
            (local.get $p)))
        (local.set $i (i32.add (local.get $i) (i32.const 1)))
        (br $SL)))
    ;; ]"}
    (local.set $p (call $wmem (local.get $p) (i32.const 0x08e6) (i32.const 3)))

    (i32.sub (local.get $p) (i32.const 0x1000)))

  ;; Hume Band has no dedicated activity mode
  (func (export "parseActivity") (param $offset i32) (param $len i32) (result i32)
    (i32.const 0))
)
