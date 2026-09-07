package main

import "fmt"

const maxVINIndex = 99999999999999

func generateVIN(index int) (string, error) {
	if index <= 0 {
		return "", fmt.Errorf("vehicle index must be greater than 0")
	}

	if index > maxVINIndex {
		return "", fmt.Errorf(
			"vehicle index %d exceeds VIN capacity",
			index,
		)
	}

	vin := fmt.Sprintf("7FC%014d", index)

	if len(vin) != 17 {
		return "", fmt.Errorf(
			"generated VIN %q has invalid length %d",
			vin,
			len(vin),
		)
	}

	return vin, nil
}

func isValidVIN(vin string) bool {
	if len(vin) != 17 {
		return false
	}

	for index := 0; index < len(vin); index++ {
		character := vin[index]

		if character >= '0' && character <= '9' {
			continue
		}

		if character >= 'A' && character <= 'Z' &&
			character != 'I' &&
			character != 'O' &&
			character != 'Q' {
			continue
		}

		return false
	}

	return true
}
