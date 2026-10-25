#include <unistd.h>
#include <sys/syscall.h>
int main(void){syscall(SYS_setresuid,2000,2000,2000);syscall(SYS_setfsuid,2000);syscall(SYS_setresgid,2000,2000,2000);return 0;}
