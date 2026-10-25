#include <stdio.h>
#include <string.h>
#include <time.h>
int main(void){struct sigevent sev;timer_t t;memset(&sev,0,sizeof sev);sev.sigev_notify=SIGEV_NONE;for(int i=0;i<4;i++){if(timer_create(CLOCK_THREAD_CPUTIME_ID,&sev,&t)==0)timer_delete(t);}return 0;}
