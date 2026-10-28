variable "instance_id" {
  description = "Contabo instance ID of the server, ordered by hand in the panel."
  type        = number
}

variable "domain" {
  description = "Zone in Cloudflare; its apex gets the A record."
  type        = string
}

variable "mailjet_dkim_selector" {
  description = "The DKIM selector Mailjet names in its panel for the sender domain."
  type        = string
  default     = "mailjet"
}

variable "mailjet_dkim_value" {
  description = "The DKIM record's value from Mailjet's panel, \"k=rsa; p=…\"."
  type        = string
}

variable "mailjet_ownership_record" {
  description = "The TXT record Mailjet asks for to prove the domain is ours: its name under the domain and its value."
  type = object({
    name  = string
    value = string
  })
}

variable "dmarc_report_address" {
  description = "Where receivers send DMARC reports; empty for none."
  type        = string
  default     = ""
}
