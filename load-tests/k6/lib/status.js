export function isSuccessfulDeploymentPoll(status) {
  return status === 200 || status === 204;
}
