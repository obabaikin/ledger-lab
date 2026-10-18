variable "instance_id" {
  description = "Contabo instance ID of the server, ordered by hand in the panel."
  type        = number
}

variable "domain" {
  description = "Zone in Cloudflare; its apex gets the A record."
  type        = string
}
