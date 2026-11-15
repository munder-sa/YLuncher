/*
 * a0call — 実行中の sdAutoReverse に Controller::sendNavFocusNotifyCmd(int) を一時的に呼ばせる実験ツール (A0)。
 *
 * ファイルシステムは一切書き換えない(/system にも触れない)。ptrace で 1 スレッドを止め、
 * レジスタを差し替えて関数を 1 回呼び、元のレジスタへ戻して detach する。デーモンを再起動すれば痕跡は残らない。
 *
 * 使い方(root):  a0call status | native | toggle [待機ms]
 *   status : 読み取りだけ(ライブラリの基底・Controller*・準備フラグ・チャネルを表示)
 *   native : sendNavFocusNotifyCmd(0)            = NAV_FOCUS_NATIVE(1) を 1 回送る
 *   toggle : (1) PROJECTED → 待機(既定 500ms) → (0) NATIVE
 * 前提: libSdAutoReverse.so が sha256 f2a665a8… の版(関数の先頭バイトを照合し、違えば何もしない)。
 *
 * ビルド: zig cc -target arm-linux-musleabihf -static -Os a0call.c -o a0call
 */
#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ptrace.h>
#include <sys/types.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define LIB "libSdAutoReverse.so"
#define FUNC_OFF 0x201308u        /* Controller::sendNavFocusNotifyCmd(int) (Thumb) */
#define G_SLOT_OFF 0x802d60u      /* グローバルハンドル g */
#define CTRL_OFF_IN_G 0xcu        /* *(g+0xc) */
#define CTRL_OFF_IN_X 0x9cu       /* *(x+0x9c) + 0xd30 = Controller* */
#define CTRL_ADD 0xd30u
#define RET_MAGIC 0xdead0000u     /* 戻り先。ここで SIGSEGV して止まる(Thumb なので lr は |1) */

static const uint8_t EXPECT_PROLOGUE[8] = {0x2d, 0xe9, 0xf0, 0x43, 0x03, 0xaf, 0x8f, 0xb0};

static int memfd = -1;

static int rd(uint32_t addr, void *buf, size_t n) {
    ssize_t r = pread(memfd, buf, n, (off_t)addr);
    return r == (ssize_t)n ? 0 : -1;
}
static int rd32(uint32_t addr, uint32_t *v) { return rd(addr, v, 4); }

static pid_t find_pid(void) {
    DIR *d = opendir("/proc");
    struct dirent *e;
    pid_t best = 0;
    while ((e = readdir(d))) {
        pid_t p = atoi(e->d_name);
        if (p <= 0) continue;
        char path[64], comm[64] = {0};
        snprintf(path, sizeof path, "/proc/%d/comm", p);
        FILE *f = fopen(path, "r");
        if (!f) continue;
        if (fgets(comm, sizeof comm, f)) {
            comm[strcspn(comm, "\n")] = 0;
            if (!strcmp(comm, "sdAutoReverse")) best = p;
        }
        fclose(f);
    }
    closedir(d);
    return best;
}

static uint32_t find_base(pid_t pid) {
    char path[64], line[512];
    snprintf(path, sizeof path, "/proc/%d/maps", pid);
    FILE *f = fopen(path, "r");
    if (!f) return 0;
    uint32_t base = 0;
    while (fgets(line, sizeof line, f)) {
        if (!strstr(line, LIB)) continue;
        unsigned long s, e, off;
        if (sscanf(line, "%lx-%lx %*s %lx", &s, &e, &off) == 3 && off == 0) {
            base = (uint32_t)s;
            break;
        }
    }
    fclose(f);
    return base;
}

static void msleep(int ms) {
    struct timespec ts = {ms / 1000, (ms % 1000) * 1000000L};
    nanosleep(&ts, NULL);
}

/* ARM32 の user_regs: r0-r15(13=sp,14=lr,15=pc), 16=cpsr, 17=orig_r0 */
typedef struct { unsigned long r[18]; } regs_t;

static int wait_stop(pid_t tid, int *sig, int timeout_ms) {
    int status;
    for (int i = 0; i < timeout_ms / 5; i++) {
        pid_t r = waitpid(tid, &status, __WALL | WNOHANG);
        if (r == tid) {
            if (WIFSTOPPED(status)) { *sig = WSTOPSIG(status); return 0; }
            return -2; /* 終了した */
        }
        msleep(5);
    }
    return -1;
}

/* 1 スレッドを止めて func(arg1, arg2) を 1 回呼ぶ(複数回呼びたいとき用に attach したまま連続実行) */
static int call_in_thread(pid_t tid, uint32_t func_thumb, uint32_t ctrl, const int *args, int nargs, int wait_ms) {
    if (ptrace(PTRACE_ATTACH, tid, 0, 0) < 0) { perror("PTRACE_ATTACH"); return -1; }
    int sig;
    if (wait_stop(tid, &sig, 3000) != 0) { fprintf(stderr, "attach の停止待ちに失敗\n"); ptrace(PTRACE_DETACH, tid, 0, 0); return -1; }

    regs_t saved;
    if (ptrace(PTRACE_GETREGS, tid, 0, &saved) < 0) { perror("PTRACE_GETREGS"); ptrace(PTRACE_DETACH, tid, 0, 0); return -1; }

    /* システムコール中に止めた場合は、再実行されるように保存側のレジスタを直す(戻すときに同じ呼び出しが再開する) */
    int32_t r0 = (int32_t)saved.r[0];
    if (r0 == -512 || r0 == -513 || r0 == -514) {
        saved.r[0] = saved.r[17];
        saved.r[15] -= (saved.r[16] & 0x20) ? 2 : 4;
        saved.r[17] = (unsigned long)-1;
    } else if (r0 == -516) {
        fprintf(stderr, "restart_syscall 中のスレッドなので中止(もう一度実行してください)\n");
        ptrace(PTRACE_DETACH, tid, 0, 0);
        return -3;
    }

    int rc = 0;
    for (int i = 0; i < nargs; i++) {
        regs_t w = saved;
        uint32_t sp = (uint32_t)((saved.r[13] & ~7ul) - 0x400);
        w.r[0] = ctrl;
        w.r[1] = (unsigned long)args[i];
        w.r[13] = sp;
        w.r[14] = RET_MAGIC | 1;
        w.r[15] = func_thumb & ~1u;
        w.r[16] = (saved.r[16] | 0x20) & ~0xf0000000ul; /* Thumb、条件フラグ消去 */
        w.r[17] = (unsigned long)-1;
        if (ptrace(PTRACE_SETREGS, tid, 0, &w) < 0) { perror("PTRACE_SETREGS"); rc = -1; break; }
        if (ptrace(PTRACE_CONT, tid, 0, 0) < 0) { perror("PTRACE_CONT"); rc = -1; break; }
        for (;;) {
            if (wait_stop(tid, &sig, 3000) != 0) { fprintf(stderr, "関数の戻り待ちがタイムアウト\n"); rc = -1; goto out; }
            regs_t now;
            ptrace(PTRACE_GETREGS, tid, 0, &now);
            if (sig == SIGSEGV && (now.r[15] & ~1ul) == RET_MAGIC) break; /* 関数が戻った */
            if (sig == SIGSEGV || sig == SIGILL || sig == SIGBUS) {
                fprintf(stderr, "想定外の %s(pc=%08lx)。レジスタを戻して中止\n", strsignal(sig), now.r[15]);
                rc = -1; goto out;
            }
            ptrace(PTRACE_CONT, tid, 0, (void *)(long)sig); /* 他のシグナルはそのまま渡す */
        }
        printf("sendNavFocusNotifyCmd(%d) を呼びました\n", args[i]);
        if (i + 1 < nargs) msleep(wait_ms);
    }
out:
    ptrace(PTRACE_SETREGS, tid, 0, &saved);
    ptrace(PTRACE_DETACH, tid, 0, 0); /* シグナルは渡さない(SIGSEGV を握りつぶす) */
    return rc;
}

int main(int argc, char **argv) {
    if (argc < 2) { fprintf(stderr, "usage: %s status|native|toggle [wait_ms]\n", argv[0]); return 2; }
    const char *mode = argv[1];
    int wait_ms = argc > 2 ? atoi(argv[2]) : 500;
    if (strcmp(mode, "status") && strcmp(mode, "native") && strcmp(mode, "toggle")) { fprintf(stderr, "unknown mode\n"); return 2; }

    pid_t pid = find_pid();
    if (!pid) { fprintf(stderr, "sdAutoReverse が動いていません\n"); return 1; }
    uint32_t base = find_base(pid);
    if (!base) { fprintf(stderr, "%s のマップが見つかりません\n", LIB); return 1; }
    char path[64];
    snprintf(path, sizeof path, "/proc/%d/mem", pid);
    memfd = open(path, O_RDONLY);
    if (memfd < 0) { perror("open mem"); return 1; }

    uint8_t pro[8];
    if (rd(base + FUNC_OFF, pro, 8) || memcmp(pro, EXPECT_PROLOGUE, 8)) {
        fprintf(stderr, "関数の先頭バイトが想定と違います(別の版のライブラリ)。何もしません\n");
        return 1;
    }
    uint32_t g = 0, x = 0, ctrl = 0;
    if (rd32(base + G_SLOT_OFF, &g) || !g) { fprintf(stderr, "ハンドル g が null(未接続)。何もしません\n"); return 1; }
    if (rd32(g + CTRL_OFF_IN_G, &x) || !x || rd32(x + CTRL_OFF_IN_X, &ctrl) || !ctrl) { fprintf(stderr, "Controller が見つかりません(未接続)。何もしません\n"); return 1; }
    ctrl += CTRL_ADD;
    uint8_t flag = 0, ch = 0;
    rd(ctrl + 4, &flag, 1);
    rd(ctrl + 5, &ch, 1);
    printf("pid=%d base=%08x g=%08x Controller=%08x ready=%u channel=%u\n", pid, base, g, ctrl, flag, ch);
    if (!strcmp(mode, "status")) return 0;
    if (!flag) { fprintf(stderr, "Controller の準備フラグが 0(バージョン交渉の前)。送っても何も出ないので中止\n"); return 1; }

    int args_native[] = {0};
    int args_toggle[] = {1, 0};
    const int *args = !strcmp(mode, "native") ? args_native : args_toggle;
    int n = !strcmp(mode, "native") ? 1 : 2;
    /* 主スレッド(tid == pid)を使う */
    return call_in_thread(pid, base + FUNC_OFF + 1, ctrl, args, n, wait_ms) ? 1 : 0;
}
