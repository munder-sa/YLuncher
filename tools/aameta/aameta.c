/*
 * aameta — 実行中の sdAutoReverse に MediaPlaybackStatusEndpoint::sendMsgPlaybackMeta を 1 回呼ばせ、
 * 有線 Android Auto の車(DHU)へ曲名・アーティスト・アルバム・曲長を送る (Task 273)。
 *
 * ファイルシステムは一切書き換えない。ptrace で主スレッドを止め、sp より下の未使用スタックに文字列と引数を置き、
 * 関数を 1 回呼んでから、そのスタック領域とレジスタを元に戻して detach する。デーモンを再起動すれば痕跡は残らない。
 *
 * 使い方(root):  aameta status | conn | send <title> <artist> <album> <duration_ms>
 *   status : 読み取りだけ(pid・endpoint・準備フラグ・チャネル)
 *   conn   : 準備済みなら "<pid>:<endpoint>" を出す(aameta-watch.sh がつなぎ直しを見分ける)。準備前は終了コード 1
 *   send   : 1 組を送る。"unKnow"(MainAiBox の空の印)は空文字列にする
 * 前提: libSdAutoReverse.so が sha256 f2a665a8… の版(関数の先頭バイトを照合し、違えば何もしない)。
 *
 * ビルド: zig cc -target arm-linux-musleabihf -static -Os aameta.c -o aameta
 */
#define _GNU_SOURCE
#include <dirent.h>
#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ptrace.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define LIB "libSdAutoReverse.so"
#define FUNC_OFF 0x202844u        /* MediaPlaybackStatusEndpoint::sendMsgPlaybackMeta (Thumb) */
#define G_SLOT_OFF 0x802d60u      /* グローバルハンドル g(a0call と同じ) */
#define X_OFF_IN_G 0xcu
#define EP_OFF_IN_X 0x74u         /* auto_reverse_send_media_playback_meta と同じたどり方 */
/* 純正の sendMsgPlaybackMeta は再生状態と同じ 0x8001 で送る誤りがある(DHU は Source 欄に album を表示した)。
 * 受信側 routeMessage は 0x8003 を MediaPlaybackMetadata として扱うので、呼ぶ間だけ movw r1,#0x8001 の imm8 を 0x03 にする。
 * 書き換わるのはこのプロセスの私有コピーのページだけで、呼び出し後に必ず戻す。この関数は純正の中から呼ばれない(公開関数も未使用) */
#define MSGID_INSN_OFF 0x2029deu
static const uint8_t EXPECT_MSGID_INSN[4] = {0x48, 0xf2, 0x01, 0x01};
#define RET_MAGIC 0xdead0000u
#define MAX_STR 512
#define SCRATCH 0x1000u           /* sp の下に使う領域(文字列 3×513 + 引数) */

static const uint8_t EXPECT_PROLOGUE[8] = {0x2d, 0xe9, 0xf0, 0x4f, 0x03, 0xaf, 0x91, 0xb0};

typedef struct { pid_t pid; uint32_t base, ep; uint8_t ready, ch; } target_t;
typedef struct { unsigned long r[18]; } regs_t; /* r0-r15, cpsr, orig_r0 */

static int memrd(pid_t pid, uint32_t addr, void *buf, size_t n) {
    char path[64];
    snprintf(path, sizeof path, "/proc/%d/mem", pid);
    int fd = open(path, O_RDONLY);
    if (fd < 0) return -1;
    ssize_t r = pread(fd, buf, n, (off_t)addr);
    close(fd);
    return r == (ssize_t)n ? 0 : -1;
}

static uint32_t find_base(pid_t pid) {
    char path[64], line[512];
    snprintf(path, sizeof path, "/proc/%d/maps", pid);
    FILE *f = fopen(path, "r");
    if (!f) return 0;
    uint32_t base = 0;
    while (fgets(line, sizeof line, f)) {
        unsigned long s, e, off;
        if (strstr(line, LIB) && sscanf(line, "%lx-%lx %*s %lx", &s, &e, &off) == 3 && off == 0) { base = (uint32_t)s; break; }
    }
    fclose(f);
    return base;
}

/* 親と子の 2 つがある。endpoint が非 null のもの(接続を持つ子)を選ぶ。複数なら pid が大きい方 */
static int find_target(target_t *t) {
    DIR *d = opendir("/proc");
    if (!d) return -1;
    struct dirent *e;
    memset(t, 0, sizeof *t);
    while ((e = readdir(d))) {
        pid_t p = atoi(e->d_name);
        if (p <= 0 || p < t->pid) continue;
        char path[64], comm[64] = {0};
        snprintf(path, sizeof path, "/proc/%d/comm", p);
        FILE *f = fopen(path, "r");
        if (!f) continue;
        int ok = fgets(comm, sizeof comm, f) && !strcmp(strtok(comm, "\n"), "sdAutoReverse");
        fclose(f);
        if (!ok) continue;
        uint32_t base = find_base(p), g = 0, x = 0, ep = 0;
        if (!base || memrd(p, base + G_SLOT_OFF, &g, 4) || !g) continue;
        if (memrd(p, g + X_OFF_IN_G, &x, 4) || !x || memrd(p, x + EP_OFF_IN_X, &ep, 4) || !ep) continue;
        uint8_t pro[8], fl[2];
        uint8_t insn[4];
        if (memrd(p, base + FUNC_OFF, pro, 8) || memcmp(pro, EXPECT_PROLOGUE, 8) ||
            memrd(p, base + MSGID_INSN_OFF, insn, 4) || memcmp(insn, EXPECT_MSGID_INSN, 4)) {
            fprintf(stderr, "関数の先頭バイトが想定と違います(別の版のライブラリ)。何もしません\n");
            closedir(d);
            return -2;
        }
        if (memrd(p, ep + 4, fl, 2)) continue;
        *t = (target_t){p, base, ep, fl[0], fl[1]};
    }
    closedir(d);
    return t->pid ? 0 : -1;
}

static void msleep(int ms) {
    struct timespec ts = {ms / 1000, (ms % 1000) * 1000000L};
    nanosleep(&ts, NULL);
}

static int wait_stop(pid_t tid, int *sig, int timeout_ms) {
    int status;
    for (int i = 0; i < timeout_ms / 5; i++) {
        pid_t r = waitpid(tid, &status, __WALL | WNOHANG);
        if (r == tid) {
            if (WIFSTOPPED(status)) { *sig = WSTOPSIG(status); return 0; }
            return -2;
        }
        msleep(5);
    }
    return -1;
}

/* "unKnow" → ""、MAX_STR バイトを超えたら UTF-8 の文字の途中で切らずに詰める */
static size_t clean(const char *s, char *out) {
    if (!strcmp(s, "unKnow")) s = "";
    size_t n = strlen(s);
    if (n > MAX_STR) {
        n = MAX_STR;
        while (n > 0 && ((unsigned char)s[n] & 0xc0) == 0x80) n--;
    }
    memcpy(out, s, n);
    out[n] = 0;
    return n + 1;
}

static int send_meta(const target_t *t, char *const strs[3], int duration_ms) {
    pid_t tid = t->pid;
    char path[64];
    snprintf(path, sizeof path, "/proc/%d/mem", tid);
    int memfd = open(path, O_RDWR);
    if (memfd < 0) { perror("open mem"); return -1; }
    if (ptrace(PTRACE_ATTACH, tid, 0, 0) < 0) { perror("PTRACE_ATTACH"); close(memfd); return -1; }
    int sig, rc = -1;
    if (wait_stop(tid, &sig, 3000) != 0) { fprintf(stderr, "attach の停止待ちに失敗\n"); ptrace(PTRACE_DETACH, tid, 0, 0); close(memfd); return -1; }

    regs_t saved;
    if (ptrace(PTRACE_GETREGS, tid, 0, &saved) < 0) { perror("PTRACE_GETREGS"); ptrace(PTRACE_DETACH, tid, 0, 0); close(memfd); return -1; }
    int32_t r0 = (int32_t)saved.r[0];
    if (r0 == -512 || r0 == -513 || r0 == -514) { /* システムコール中: 戻したときに再実行させる(a0call と同じ) */
        saved.r[0] = saved.r[17];
        saved.r[15] -= (saved.r[16] & 0x20) ? 2 : 4;
        saved.r[17] = (unsigned long)-1;
    } else if (r0 == -516) {
        fprintf(stderr, "restart_syscall 中のスレッドなので中止(もう一度実行してください)\n");
        ptrace(PTRACE_DETACH, tid, 0, 0);
        close(memfd);
        return -3;
    }

    /* 配置: [lo .. lo+0x20) スタック引数、その上に文字列。lo は元の sp から 0x400 空けた下 */
    uint32_t top = (uint32_t)((saved.r[13] & ~7ul) - 0x400), lo = (top - SCRATCH) & ~7u;
    static uint8_t backup[SCRATCH], img[SCRATCH];
    const uint8_t id_new = 0x03, id_old = EXPECT_MSGID_INSN[2]; /* goto より前に置く */
    if (pread(memfd, backup, SCRATCH, lo) != SCRATCH) { perror("スタックの退避"); goto restore_regs; }
    memset(img, 0, SCRATCH);
    uint32_t off = 0x20, addr[4];
    for (int i = 0; i < 3; i++) { addr[i] = lo + off; off += clean(strs[i], (char *)img + off); }
    addr[3] = lo + off; /* "" (s4 と bytes に共用) */
    uint32_t stk[5] = {addr[3], addr[3], 0, (uint32_t)duration_ms, 0};
    memcpy(img, stk, sizeof stk);
    if (pwrite(memfd, img, SCRATCH, lo) != SCRATCH) { perror("スタックへの書き込み"); goto restore_mem; }
    if (pwrite(memfd, &id_new, 1, t->base + MSGID_INSN_OFF + 2) != 1) { perror("メッセージ番号の差し替え"); goto restore_mem; }

    regs_t w = saved;
    w.r[0] = t->ep;
    w.r[1] = addr[0]; /* song */
    w.r[2] = addr[2]; /* album(DHU の表示で確認: 第3引数がアーティスト欄) */
    w.r[3] = addr[1]; /* artist */
    w.r[13] = lo;
    w.r[14] = RET_MAGIC | 1;
    w.r[15] = (t->base + FUNC_OFF) & ~1u;
    w.r[16] = (saved.r[16] | 0x20) & ~0xf0000000ul;
    w.r[17] = (unsigned long)-1;
    if (ptrace(PTRACE_SETREGS, tid, 0, &w) < 0 || ptrace(PTRACE_CONT, tid, 0, 0) < 0) { perror("PTRACE_SETREGS/CONT"); goto restore_mem; }
    for (;;) {
        if (wait_stop(tid, &sig, 3000) != 0) { fprintf(stderr, "関数の戻り待ちがタイムアウト\n"); goto restore_mem; }
        regs_t now;
        ptrace(PTRACE_GETREGS, tid, 0, &now);
        if (sig == SIGSEGV && (now.r[15] & ~1ul) == RET_MAGIC) { rc = 0; break; }
        if (sig == SIGSEGV || sig == SIGILL || sig == SIGBUS) { fprintf(stderr, "想定外の %s(pc=%08lx)。元に戻して中止\n", strsignal(sig), now.r[15]); goto restore_mem; }
        ptrace(PTRACE_CONT, tid, 0, (void *)(long)sig);
    }
restore_mem:
    if (pwrite(memfd, &id_old, 1, t->base + MSGID_INSN_OFF + 2) != 1) perror("メッセージ番号の書き戻し");
    if (pwrite(memfd, backup, SCRATCH, lo) != SCRATCH) perror("スタックの書き戻し");
restore_regs:
    ptrace(PTRACE_SETREGS, tid, 0, &saved);
    ptrace(PTRACE_DETACH, tid, 0, 0); /* 戻り検出の SIGSEGV は渡さない */
    close(memfd);
    return rc;
}

static int usage(const char *a0) {
    fprintf(stderr, "usage: %s status | conn | send <title> <artist> <album> <duration_ms>\n", a0);
    return 2;
}

int main(int argc, char **argv) {
    if (argc < 2) return usage(argv[0]);
    const char *mode = argv[1];
    int is_status = !strcmp(mode, "status"), is_conn = !strcmp(mode, "conn"), is_send = !strcmp(mode, "send");
    if (!(is_status && argc == 2) && !(is_conn && argc == 2) && !(is_send && argc == 6)) return usage(argv[0]);
    long dur = 0;
    if (is_send) {
        char *end;
        dur = strtol(argv[5], &end, 10);
        if (!*argv[5] || *end || dur < 0 || dur > 0x7fffffffL) return usage(argv[0]);
    }

    target_t t;
    int f = find_target(&t);
    if (f == -2) return 1;
    if (f) { if (!is_conn) fprintf(stderr, "sdAutoReverse が未接続(endpoint が null)。何もしません\n"); return 1; }
    if (is_status) {
        printf("pid=%d base=%08x endpoint=%08x ready=%u channel=%u\n", t.pid, t.base, t.ep, t.ready, t.ch);
        return 0;
    }
    if (!t.ready) { if (!is_conn) fprintf(stderr, "endpoint の準備フラグが 0。送っても何も出ないので中止\n"); return 1; }
    if (is_conn) { printf("%d:%08x\n", t.pid, t.ep); return 0; }

    if (send_meta(&t, argv + 2, (int)dur)) return 1;
    printf("sent title=%s artist=%s album=%s duration_ms=%ld\n", argv[2], argv[3], argv[4], dur);
    return 0;
}
