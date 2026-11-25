#include "wire.h"

#include <string.h>
#include <sys/wait.h>
#include <time.h>

static int supervise(const char *service_pid, const char *timeout, char **command) {
    if (!cv_watch_parent(service_pid)) return 1;
    char *end = NULL;
    errno = 0;
    long milliseconds = strtol(timeout, &end, 10);
    if (errno != 0 || *end != '\0' || milliseconds <= 0) return 1;

    sigset_t signals;
    if (signal(SIGTERM, SIG_DFL) == SIG_ERR || sigemptyset(&signals) != 0 || sigaddset(&signals, SIGTERM) != 0 ||
        sigprocmask(SIG_UNBLOCK, &signals, NULL) != 0) {
        return 1;
    }
    char parent[32];
    int length = snprintf(parent, sizeof(parent), "%d", getpid());
    if (length <= 0 || (size_t)length >= sizeof(parent) || setenv(CV_PARENT_ENV, parent, 1) != 0) {
        return 1;
    }
    struct sigevent expiry = {.sigev_notify = SIGEV_SIGNAL, .sigev_signo = SIGKILL};
    struct itimerspec deadline = {.it_value = {.tv_sec = milliseconds / 1000,
                                              .tv_nsec = (milliseconds % 1000) * 1000000}};
    timer_t watchdog;
    if (timer_create(CLOCK_BOOTTIME, &expiry, &watchdog) != 0 || timer_settime(watchdog, 0, &deadline, NULL) != 0) {
        return 1;
    }

    pid_t child = fork();
    if (child < 0) return 1;
    if (child == 0) {
        if (!cv_watch_parent(parent)) _Exit(1);
        execv(command[0], command);
        perror("Core Vision worker exec");
        _Exit(127);
    }

    close(STDIN_FILENO);
    close(STDOUT_FILENO);
    int status;
    pid_t waited;
    do {
        waited = waitpid(child, &status, 0);
    } while (waited < 0 && errno == EINTR);
    return waited == child && WIFEXITED(status) ? WEXITSTATUS(status) : 1;
}

int main(int argc, char **argv) {
    if (argc >= 5 && strcmp(argv[1], "--supervise") == 0) return supervise(argv[2], argv[3], argv + 4);
    CvRequest request;
    uint8_t rgba[16];
    if (argc != 1 || !cv_watch_parent(getenv(CV_PARENT_ENV)) || !cv_read(STDIN_FILENO, &request, sizeof(request)) ||
        !cv_valid(&request, CV_PROBE) || !cv_read(STDIN_FILENO, rgba, sizeof(rgba))) {
        return 1;
    }

    uint8_t alpha[4];
    for (size_t index = 0; index < sizeof(alpha); ++index) {
        size_t pixel = index * 4;
        alpha[index] = rgba[pixel] ^ rgba[pixel + 1] ^ rgba[pixel + 2] ^ rgba[pixel + 3];
    }
    char context[128] = "unavailable";
    cv_context(context, sizeof(context));
    char metadata[256];
    int size = snprintf(metadata, sizeof(metadata), "pid=%d\nuid=%d\npageSize=%ld\nselinux=%s\n",
                        getpid(), (int)getuid(), sysconf(_SC_PAGESIZE), context);
    if (size < 0 || (size_t)size >= sizeof(metadata)) return 2;
    CvResponse response = {CV_MAGIC, CV_VERSION, request.id, 0, 2, 2, sizeof(alpha), 0, 0, (uint32_t)size};
    return cv_write(STDOUT_FILENO, &response, sizeof(response)) &&
           cv_write(STDOUT_FILENO, metadata, (size_t)size) &&
           cv_write(STDOUT_FILENO, alpha, sizeof(alpha)) ? 0 : 3;
}
