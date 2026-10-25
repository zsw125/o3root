import io, sys, re
src = open('t34.c', encoding='utf-8', errors='surrogateescape').read()

# 1) rename identifiers/strings
src = src.replace('t34_rootd', 'o3p_rootd').replace('t34_proof.txt', 'o3p_proof.txt')
src = src.replace('"t34"', '"o3p"').replace('"t34p"', '"o3pp"')
src = src.replace('t34_post.sh', 'o3p_post.sh').replace('t34_post.out', 'o3p_post.out')

# 2) include symbol table header
src = src.replace('#include <linux/capability.h>',
                  '#include <linux/capability.h>\n#include "ksu_symtab.h"', 1)

KSU_CODE = r'''
/* ==== KernelSU LKM: runtime relink + finit_module ==== */
#define KSU_SHN_ABS 0xfff1

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
    if(type!=2)continue;
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
  printf("KSU_PATCH symtabs=%d found=%d missing=%d\n",strs,found,missing);fflush(stdout);
  return found>0?0:-6;
}

static void ksu_modlist(void){
  int fd=open("/proc/modules",O_RDONLY);
  if(fd<0){printf("KSU_MODULE UNKNOWN\n");fflush(stdout);return;}
  char b[8192]; ssize_t n=read(fd,b,sizeof b-1); close(fd);
  if(n<=0){printf("KSU_MODULE UNKNOWN\n");fflush(stdout);return;}
  b[n]=0;
  printf("KSU_MODULE %s\n",strstr(b,"kernelsu")?"LIVE":"ABSENT");fflush(stdout);
}

static int ksu_load(unsigned long long slide){
  size_t len=0;
  unsigned char *b=ksu_blob_load(&len);
  if(!b){printf("KSU_BLOB MISSING\n");fflush(stdout);return -1;}
  printf("KSU_BLOB len=%zu slide=0x%llx\n",len,slide);fflush(stdout);
  int r=ksu_patch(b,len,slide);
  if(r){printf("KSU_PATCH_FAIL r=%d\n",r);fflush(stdout);free(b);return -1;}
  const char *out="/data/local/tmp/o3p_ksu.ko";
  int fd=open(out,O_WRONLY|O_CREAT|O_TRUNC,0644);
  if(fd<0){printf("KSU_WRITE_FAIL errno=%d\n",errno);fflush(stdout);free(b);return -1;}
  size_t w=0;
  while(w<len){ssize_t x=write(fd,b+w,len-w); if(x<=0)break; w+=x;}
  close(fd); free(b);
  printf("KSU_WROTE %s %zu\n",out,w);fflush(stdout);
  if(w!=len)return -1;
  int kfd=open(out,O_RDONLY|O_CLOEXEC);
  if(kfd<0){printf("KSU_OPEN_FAIL errno=%d\n",errno);fflush(stdout);return -1;}
  errno=0;
  long rc=syscall(273,kfd,"",0);
  int e=errno;
  close(kfd);
  printf("KSU_INSMOD rc=%ld errno=%d\n",rc,e);fflush(stdout);
  ksu_modlist();
  return (int)rc;
}

'''
anchor = 'static void reap_calib(pid_t pid){'
assert anchor in src
src = src.replace(anchor, KSU_CODE + anchor, 1)

# 3) call ksu_load inside the g_armed block, right after root_proof();
old = ''' if(g_armed){
  root_proof();'''
new = ''' if(g_armed){
  root_proof();
  if(g_un){ ksu_load(g_un-INIT_USER_NS_STATIC); } else { printf("KSU_SKIP no_slide\n");fflush(stdout); }'''
assert old in src
src = src.replace(old, new, 1)

open('o3p.c','w',encoding='utf-8',errors='surrogateescape').write(src)
print("wrote o3p.c", len(src), "bytes")
