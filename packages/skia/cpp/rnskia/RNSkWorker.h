#pragma once

#include <deque>
#include <functional>
#include <mutex>
#include <string>
#include <thread>
#include <utility>

#include <condition_variable>

#if defined(__APPLE__)
#include <pthread.h>
extern "C" void *objc_autoreleasePoolPush(void);
extern "C" void objc_autoreleasePoolPop(void *pool);
#elif defined(__ANDROID__)
#include <pthread.h>
#endif

namespace RNSkia {

/**
 * One background thread for work that neither a frame nor JS should wait
 * for: decoding an image, for instance. Jobs run in submission order, one at
 * a time, so a burst of them never fans out into as many threads.
 */
class RNSkWorker {
public:
  static RNSkWorker &getInstance() {
    // Never destroyed: the thread is detached and the process takes it down,
    // which spares the static destructor from joining it at exit.
    static auto *instance = new RNSkWorker("RNSkia Worker");
    return *instance;
  }

  /**
   * A worker of its own, on a thread named `name`, for jobs that must not
   * wait behind the shared worker's (see getInstance). Never destroyed, like
   * the shared one: the caller keeps the reference for the process's life.
   */
  static RNSkWorker &makeDedicated(const char *name) {
    return *new RNSkWorker(name);
  }

  RNSkWorker(const RNSkWorker &) = delete;
  RNSkWorker &operator=(const RNSkWorker &) = delete;

  void post(std::function<void()> job) {
    {
      std::lock_guard<std::mutex> lock(_mutex);
      _jobs.push_back(std::move(job));
    }
    _condition.notify_one();
  }

private:
  explicit RNSkWorker(const char *name) : _name(name) {
    std::thread([this]() { run(); }).detach();
  }

  void run() {
#if defined(__APPLE__)
    pthread_setname_np(_name.c_str());
#elif defined(__ANDROID__)
    pthread_setname_np(pthread_self(), _name.c_str());
#endif
    for (;;) {
      std::function<void()> job;
      {
        std::unique_lock<std::mutex> lock(_mutex);
        _condition.wait(lock, [this]() { return !_jobs.empty(); });
        job = std::move(_jobs.front());
        _jobs.pop_front();
      }
      runInAutoreleasePool(job);
    }
  }

  // A thread made here has no autorelease pool, and a job calls into
  // Objective-C on Apple platforms (Dawn's Metal backend, a presented
  // image's CGImage): what it autoreleases is drained as it ends.
  static void runInAutoreleasePool(const std::function<void()> &job) {
#if defined(__APPLE__)
    void *pool = objc_autoreleasePoolPush();
    job();
    objc_autoreleasePoolPop(pool);
#else
    job();
#endif
  }

  // The thread's name: 15 characters at most, as Linux's
  // pthread_setname_np takes no longer one.
  std::string _name;
  std::mutex _mutex;
  std::condition_variable _condition;
  std::deque<std::function<void()>> _jobs;
};

} // namespace RNSkia
