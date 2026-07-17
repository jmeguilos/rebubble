// Runs on the Maestro host (not the emulator). Hits the fixture server on localhost.
const body = JSON.stringify({
  chatGuid: "iMessage;-;+15550100001",
  text: "Maestro fixture hello",
});
const response = http.post("http://127.0.0.1:12346/_fixture/receive", {
  headers: { "Content-Type": "application/json" },
  body,
});
if (response.status !== 200) {
  throw new Error("fixture receive failed: HTTP " + response.status + " " + response.body);
}
