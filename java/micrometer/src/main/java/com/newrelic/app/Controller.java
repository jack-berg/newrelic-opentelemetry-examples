package com.newrelic.app;

import java.util.Random;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class Controller {

  @GetMapping("/ping")
  public String ping() throws InterruptedException {
    doWork();
    return "pong";
  }

  private void doWork() throws InterruptedException {
    Thread.sleep(new Random().nextInt(1000));
  }
}
