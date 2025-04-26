# NI2 Clock
**Clock screensaver app for Android, originally used for my internal network monitoring.**
## Background
This is a part of an experiment I am current doing, in which I will ask AI to write all the code, the generated code goes straight into the IDE, without any changes from a human. The goal of this app is simple: a clock screensaver that's meant to run fullscreen on old Android phones with burn-in protection and extra information panels like the current weather(pulled from GPS location and weather API), key performance data on my Proxmox server and OpenWRT routers, or even real-time graphs and warnings from my Zabbix server.
As far as this version, it was already better than I thought. The App is still lacking a lot of features, and the Zabbix panel wouldn't show up despite being in the code. I'm using both ChatGPT(4o) and Claude(Sonnet) to do the coding for me, but sometimes they just insist that they're right even in face of the errors I presented to them. Maybe this project is a bit too hard for AI today to do, or maybe it's because I'm trying to run this App on ancient Android versions(4.3 and 4.4). While I do not know the exact reason for that, I would keep on using 100% AI to finish up this project in order to see how capable they really are.
## Function
It is designed to run on my internal networks, but theoratically anyone that has a Zabbix server and Linux servers with SSH access should also be able to use this app.
Current features include:
- Time and date display
- Clock moving every minute to prevent burn-ins
- Real-time weather display(activated on swipe gesture)
## To-dos
- [ ] Add proper Zabbix integration
- [ ] Add CPU and memory usage display
- [ ] Add a settings menu
- [ ] Add an App icon :)
